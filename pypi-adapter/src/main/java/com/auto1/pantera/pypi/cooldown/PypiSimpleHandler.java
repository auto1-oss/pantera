/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.pypi.cooldown;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.metadata.MetadataParseException;
import com.auto1.pantera.cooldown.metadata.MetadataRewriteException;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;

import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates cooldown-aware filtering of PyPI {@code /simple/<name>/}
 * responses — the PEP 503 HTML Simple Index surface that pip queries by
 * default.
 *
 * <p>This handler is where {@link PypiMetadataParser},
 * {@link PypiMetadataFilter} and {@link PypiMetadataRewriter} — the
 * trio registered via the cooldown SPI in {@code CooldownWiring} — are
 * actually consumed on the serve path. Prior to this class, the
 * registered {@code pypiBundle} was dead infrastructure: the PyPI
 * proxy slice filtered nothing on {@code /simple/} requests and blocked
 * versions leaked straight through to pip.</p>
 *
 * <p>Mirrors the Go handler pattern introduced in commit {@code 1eb53ceb}
 * ({@code GoListHandler} for {@code /@v/list}). The dispatch order in
 * the PyPI proxy slice must route JSON API requests through
 * {@link PypiJsonHandler} and Simple Index requests through this class
 * before the generic upstream-fetch flow.</p>
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>Fetch {@code /simple/<name>/} from upstream via the shared
 *       slice (same auth / cache / resilience layers as artifact
 *       fetches).</li>
 *   <li>On non-2xx, forward status + body unchanged.</li>
 *   <li>Parse the HTML via {@link PypiMetadataParser}. On parse
 *       failure, pass upstream bytes through unchanged.</li>
 *   <li>Evaluate every parsed version against cooldown; collect
 *       the blocked set.</li>
 *   <li>Run the filter; re-serialise via {@link PypiMetadataRewriter}
 *       (PEP 503 HTML, {@code text/html} content type).</li>
 *   <li>If every version is blocked → 404, which is pip's convention
 *       for "package not available" and triggers its usual retry /
 *       error path cleanly.</li>
 * </ol>
 *
 * @since 2.2.0
 */
public final class PypiSimpleHandler {

    /**
     * Upstream slice shared with the main PyPI proxy.
     */
    private final Slice upstream;

    /**
     * Cooldown evaluation service.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown inspector (release-date lookups).
     */
    private final CooldownInspector inspector;

    /**
     * Repository type (e.g. {@code "pypi"}, {@code "pypi-proxy"}).
     */
    private final String repoType;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Path detector for {@code /simple/<name>/} endpoints.
     */
    private final PypiMetadataRequestDetector detector;

    /**
     * HTML parser for PEP 503 Simple Index bodies.
     */
    private final PypiMetadataParser parser;

    /**
     * Pure filter that drops blocked versions.
     */
    private final PypiMetadataFilter filter;

    /**
     * Serialiser back to PEP 503 HTML.
     */
    private final PypiMetadataRewriter rewriter;

    /**
     * Ctor.
     *
     * @param upstream Upstream PyPI proxy slice
     * @param cooldown Cooldown evaluation service
     * @param inspector Cooldown inspector
     * @param repoType Repository type (e.g. {@code "pypi-proxy"})
     * @param repoName Repository name
     */
    public PypiSimpleHandler(
        final Slice upstream,
        final CooldownService cooldown,
        final CooldownInspector inspector,
        final String repoType,
        final String repoName
    ) {
        this.upstream = upstream;
        this.cooldown = cooldown;
        this.inspector = inspector;
        this.repoType = repoType;
        this.repoName = repoName;
        this.detector = new PypiMetadataRequestDetector();
        this.parser = new PypiMetadataParser();
        this.filter = new PypiMetadataFilter();
        this.rewriter = new PypiMetadataRewriter();
    }

    /**
     * Whether this handler should intercept the given request path.
     *
     * @param path Request path
     * @return true for {@code /simple/<name>/} paths with a non-empty
     *     package name
     */
    public boolean matches(final String path) {
        return this.detector.isMetadataRequest(path)
            && this.detector.extractPackageName(path).isPresent();
    }

    /**
     * Handle a Simple Index request with cooldown filtering.
     *
     * @param line Request line (must be {@code /simple/<name>/})
     * @param user Authenticated user (for cooldown bookkeeping)
     * @return Future response
     */
    public CompletableFuture<Response> handle(final RequestLine line, final String user) {
        final String path = line.uri().getPath();
        final String pkg = this.detector.extractPackageName(path).orElseThrow(
            () -> new IllegalArgumentException("Not a /simple/ path: " + path)
        );
        return this.upstream.response(line, Headers.EMPTY, Content.EMPTY)
            .thenCompose(resp -> {
                if (!resp.status().success()) {
                    return bodyBytes(resp.body()).thenApply(bytes ->
                        ResponseBuilder.from(resp.status())
                            .headers(resp.headers())
                            .body(bytes)
                            .build()
                    );
                }
                return bodyBytes(resp.body()).thenCompose(bytes ->
                    this.processUpstream(bytes, pkg, user)
                );
            });
    }

    /**
     * Parse → evaluate → filter → serialise. Pass-through on parse
     * failure; 404 when every version is blocked.
     */
    private CompletableFuture<Response> processUpstream(
        final byte[] upstreamBytes, final String pkg, final String user
    ) {
        final PypiSimpleIndex parsed;
        try {
            parsed = this.parser.parse(upstreamBytes);
        } catch (final MetadataParseException ex) {
            EcsLogger.warn("com.auto1.pantera.pypi")
                .message("Failed to parse /simple/ HTML — passing upstream bytes through")
                .eventCategory("web")
                .eventAction("simple_filter")
                .eventOutcome("success")
                .field("event.reason", "upstream_malformed")
                .field("repository.name", this.repoName)
                .field("package.name", pkg)
                .error(ex)
                .log();
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Type", this.rewriter.contentType())
                    .body(upstreamBytes)
                    .build()
            );
        }
        final List<String> versions = this.parser.extractVersions(parsed);
        if (versions.isEmpty()) {
            // Empty index — nothing to filter. Serve upstream verbatim
            // so PEP 691 JSON / PEP 503 HTML quirks round-trip exactly.
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Type", this.rewriter.contentType())
                    .body(upstreamBytes)
                    .build()
            );
        }
        return this.blockedVersions(pkg, versions, user).thenApply(blocked -> {
            if (blocked.isEmpty()) {
                // Fast path: forward the upstream bytes verbatim so any
                // formatting / ordering quirks round-trip.
                return ResponseBuilder.ok()
                    .header("Content-Type", this.rewriter.contentType())
                    .body(upstreamBytes)
                    .build();
            }
            final PypiSimpleIndex filtered = this.filter.filter(parsed, blocked);
            if (filtered.links().isEmpty()) {
                return this.allBlockedResponse(pkg);
            }
            try {
                final byte[] body = this.rewriter.rewrite(filtered);
                EcsLogger.info("com.auto1.pantera.pypi")
                    .message("/simple/ filtered: removed cooldown-blocked versions"
                        + " (total=" + versions.size()
                        + ", blocked=" + blocked.size()
                        + ", served_links=" + filtered.links().size() + ")")
                    .eventCategory("web")
                    .eventAction("simple_filter")
                    .eventOutcome("success")
                    .field("repository.name", this.repoName)
                    .field("package.name", pkg)
                    .log();
                return ResponseBuilder.ok()
                    .header("Content-Type", this.rewriter.contentType())
                    .body(body)
                    .build();
            } catch (final MetadataRewriteException ex) {
                EcsLogger.warn("com.auto1.pantera.pypi")
                    .message("/simple/ rewrite failed — falling back to upstream body")
                    .eventCategory("web")
                    .eventAction("simple_filter")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("package.name", pkg)
                    .error(ex)
                    .log();
                return ResponseBuilder.ok()
                    .header("Content-Type", this.rewriter.contentType())
                    .body(upstreamBytes)
                    .build();
            }
        });
    }

    /**
     * Every version blocked — 404. pip handles 404 on {@code /simple/}
     * as "package not found" and surfaces a clean error; returning an
     * empty HTML index would work but is more likely to produce weird
     * secondary failures in some client versions.
     */
    private Response allBlockedResponse(final String pkg) {
        EcsLogger.info("com.auto1.pantera.pypi")
            .message("/simple/ has no non-blocked versions — returning 404")
            .eventCategory("web")
            .eventAction("simple_filter")
            .eventOutcome("failure")
            .field("event.reason", "all_versions_blocked")
            .field("repository.name", this.repoName)
            .field("package.name", pkg)
            .log();
        return ResponseBuilder.notFound()
            .header("X-Pantera-Cooldown", "all-blocked")
            .textBody(
                "All versions of '" + pkg
                    + "' are under cooldown; no versions available."
            )
            .build();
    }

    /**
     * Evaluate every candidate against cooldown in parallel; collect
     * the blocked set. Swallows per-version errors to "allowed" so a
     * transient inspector failure never denies an entire index.
     */
    private CompletableFuture<Set<String>> blockedVersions(
        final String pkg, final List<String> candidates, final String user
    ) {
        final List<CompletableFuture<Boolean>> futures =
            new ArrayList<>(candidates.size());
        for (final String version : candidates) {
            futures.add(this.isBlocked(pkg, version, user));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                final Set<String> blocked = new HashSet<>();
                for (int idx = 0; idx < candidates.size(); idx++) {
                    if (futures.get(idx).join()) {
                        blocked.add(candidates.get(idx));
                    }
                }
                return blocked;
            });
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private CompletableFuture<Boolean> isBlocked(
        final String pkg, final String version, final String user
    ) {
        final CooldownRequest req = new CooldownRequest(
            this.repoType,
            this.repoName,
            pkg,
            version,
            user == null ? "pypi-simple" : user,
            Instant.now()
        );
        return this.cooldown.evaluate(req, this.inspector)
            .thenApply(result -> result.blocked())
            .exceptionally(err -> {
                EcsLogger.warn("com.auto1.pantera.pypi")
                    .message("Cooldown evaluation failed; treating version as allowed")
                    .eventCategory("database")
                    .eventAction("cooldown_evaluate")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("package.name", pkg)
                    .field("package.version", version)
                    .error(err)
                    .log();
                return false;
            });
    }

    /**
     * Drain a reactive-streams body to a byte array. Mirrors the helper
     * in the Go cooldown handlers.
     */
    private static CompletableFuture<byte[]> bodyBytes(
        final org.reactivestreams.Publisher<ByteBuffer> body
    ) {
        return Flowable.fromPublisher(body)
            .reduce(new ByteArrayOutputStream(), (stream, buffer) -> {
                try {
                    stream.write(new Remaining(buffer).bytes());
                    return stream;
                } catch (final java.io.IOException error) {
                    throw new UncheckedIOException(error);
                }
            })
            .map(ByteArrayOutputStream::toByteArray)
            .onErrorReturnItem(new byte[0])
            .to(SingleInterop.get())
            .toCompletableFuture();
    }
}
