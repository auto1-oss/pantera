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
package com.auto1.pantera.http.cooldown;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.metadata.MetadataParseException;
import com.auto1.pantera.cooldown.metadata.VersionComparators;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;

import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates cooldown-aware rewriting of Go {@code /@latest} responses.
 *
 * <p>Closes the "unbounded-resolution gap" where {@code go get <module>}
 * (without a pseudo-version) hits {@code /@latest} and never consults
 * {@code /@v/list}. The flow is:</p>
 *
 * <ol>
 *   <li>Fetch {@code /@latest} from upstream.</li>
 *   <li>Parse the JSON into {@link GoLatestInfo}. If malformed, pass the
 *       upstream bytes through unchanged — we never break clients on
 *       upstream weirdness.</li>
 *   <li>Check the {@code Version} against cooldown. If not blocked,
 *       return the upstream response unchanged.</li>
 *   <li>If blocked, fetch the sibling {@code /@v/list}, evaluate every
 *       version against cooldown, and pick the highest non-blocked one
 *       under the same semver-ish ordering the Go toolchain uses
 *       ({@link VersionComparators#semver()}, which handles {@code v}
 *       prefix and tolerates pseudo-versions).</li>
 *   <li>Rewrite the {@code @latest} JSON with the fallback version,
 *       preserving {@code Origin}; clear {@code Time} because it no
 *       longer matches the served version and the Go client treats the
 *       field as optional.</li>
 *   <li>If <em>every</em> version is blocked, return HTTP 403 with a
 *       Go-client-parseable text body — consistent with the per-version
 *       block response produced by
 *       {@link GoCooldownResponseFactory}.</li>
 * </ol>
 *
 * <p>The handler reuses the same upstream {@link Slice} the proxy already
 * uses for artifact fetches — cache/auth/resilience layers therefore apply
 * to the list fallback too.</p>
 *
 * @since 2.2.0
 */
public final class GoLatestHandler {

    /**
     * Max versions to evaluate when picking the fallback. Matches the
     * default {@code MetadataFilterService.DEFAULT_MAX_VERSIONS} so
     * operators see consistent cooldown-evaluation cost across adapters.
     */
    private static final int MAX_VERSIONS_TO_EVALUATE = 50;

    /**
     * Upstream slice, shared with the main Go proxy. Using the same
     * slice means the list-fallback fetch picks up the same auth /
     * cache layers and avoids divergent behaviour.
     */
    private final Slice upstream;

    /**
     * Cooldown service for block evaluation.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown inspector for release-date lookups.
     */
    private final CooldownInspector inspector;

    /**
     * Repository type (e.g. {@code "go"}, {@code "go-proxy"}).
     */
    private final String repoType;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Path detector for {@code /@latest} endpoints.
     */
    private final GoLatestMetadataRequestDetector detector;

    /**
     * JSON parser for {@code @latest} bodies.
     */
    private final GoLatestMetadataParser parser;

    /**
     * Local filter (pure, no I/O).
     */
    private final GoLatestMetadataFilter filter;

    /**
     * JSON serialiser for the rewritten body.
     */
    private final GoLatestMetadataRewriter rewriter;

    /**
     * Constructor.
     *
     * @param upstream Upstream Go module proxy slice
     * @param cooldown Cooldown evaluation service
     * @param inspector Cooldown inspector for release-date lookups
     * @param repoType Repository type identifier (e.g. {@code "go"})
     * @param repoName Repository name
     */
    public GoLatestHandler(
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
        this.detector = new GoLatestMetadataRequestDetector();
        this.parser = new GoLatestMetadataParser();
        this.filter = new GoLatestMetadataFilter();
        this.rewriter = new GoLatestMetadataRewriter();
    }

    /**
     * Whether the handler should intercept the given path.
     *
     * @param path Request path
     * @return true for {@code /<module>/@latest} paths with a non-empty module
     */
    public boolean matches(final String path) {
        return this.detector.isMetadataRequest(path)
            && this.detector.extractPackageName(path).isPresent();
    }

    /**
     * Handle a {@code /@latest} request with cooldown-aware fallback.
     *
     * @param line Request line (must be an {@code /@latest} path)
     * @param user Authenticated user (for cooldown bookkeeping)
     * @return Future response
     */
    public CompletableFuture<Response> handle(final RequestLine line, final String user) {
        final String path = line.uri().getPath();
        final String module = this.detector.extractPackageName(path).orElseThrow(
            () -> new IllegalArgumentException("Not a @latest path: " + path)
        );
        return this.upstream.response(line, Headers.EMPTY, Content.EMPTY)
            .thenCompose(resp -> {
                if (!resp.status().success()) {
                    // Non-200 from upstream — forward body and status, no rewrite.
                    return bodyBytes(resp.body()).thenApply(bytes ->
                        ResponseBuilder.from(resp.status())
                            .headers(resp.headers())
                            .body(bytes)
                            .build()
                    );
                }
                return bodyBytes(resp.body()).thenCompose(bytes ->
                    this.processUpstream(bytes, resp.headers(), module, user)
                );
            });
    }

    /**
     * Process a successful upstream response: pass-through when allowed,
     * rewrite when the version is blocked, 403 when nothing resolves.
     */
    private CompletableFuture<Response> processUpstream(
        final byte[] upstreamBytes,
        final Headers upstreamHeaders,
        final String module,
        final String user
    ) {
        final GoLatestInfo info;
        try {
            info = this.parser.parse(upstreamBytes);
        } catch (final MetadataParseException ex) {
            EcsLogger.warn("com.auto1.pantera.go")
                .message("Failed to parse @latest JSON — passing upstream body through")
                .eventCategory("web")
                .eventAction("latest_filter")
                .eventOutcome("success")
                .field("event.reason", "upstream_malformed")
                .field("repository.name", this.repoName)
                .field("package.name", module)
                .error(ex)
                .log();
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .headers(upstreamHeaders)
                    .body(upstreamBytes)
                    .build()
            );
        }
        return this.isBlocked(module, info.version(), user).thenCompose(blocked -> {
            if (!blocked) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .headers(upstreamHeaders)
                        .body(upstreamBytes)
                        .build()
                );
            }
            EcsLogger.info("com.auto1.pantera.go")
                .message("@latest version blocked by cooldown; resolving fallback")
                .eventCategory("web")
                .eventAction("latest_filter")
                .eventOutcome("success")
                .field("event.reason", "version_blocked")
                .field("repository.name", this.repoName)
                .field("package.name", module)
                .field("package.version", info.version())
                .log();
            return this.resolveFallback(info, module, user);
        });
    }

    /**
     * Fetch {@code @v/list}, evaluate every candidate, and emit a
     * rewritten {@code @latest} JSON for the highest non-blocked version.
     * Returns 403 when the list is empty or every candidate is blocked.
     */
    private CompletableFuture<Response> resolveFallback(
        final GoLatestInfo upstreamInfo,
        final String module,
        final String user
    ) {
        final String listPath = "/" + module + "/@v/list";
        final RequestLine listLine = new RequestLine(RqMethod.GET, listPath);
        return this.upstream.response(listLine, Headers.EMPTY, Content.EMPTY)
            .thenCompose(listResp -> {
                if (!listResp.status().success()) {
                    // List fetch failed — consume body and fall through to 403.
                    return bodyBytes(listResp.body()).thenApply(b -> List.<String>of());
                }
                return bodyBytes(listResp.body()).thenApply(this::parseVersionList);
            })
            .thenCompose(candidates -> this.pickHighestNonBlocked(candidates, module, user))
            .thenApply(pickedOpt -> {
                if (pickedOpt.isEmpty()) {
                    return this.allBlockedResponse(module);
                }
                final String picked = pickedOpt.get();
                if (picked.equals(upstreamInfo.version())) {
                    // Shouldn't happen (we got here because upstream was blocked),
                    // but guard against a race with the block being lifted.
                    return ResponseBuilder.ok()
                        .header("Content-Type", this.rewriter.contentType())
                        .body(this.rewriter.rewrite(upstreamInfo))
                        .build();
                }
                final GoLatestInfo rewritten = this.filter.updateLatest(upstreamInfo, picked);
                EcsLogger.info("com.auto1.pantera.go")
                    .message("@latest rewritten to non-blocked fallback")
                    .eventCategory("web")
                    .eventAction("latest_filter")
                    .eventOutcome("success")
                    .field("repository.name", this.repoName)
                    .field("package.name", module)
                    .field("package.version", picked)
                    .log();
                return ResponseBuilder.ok()
                    .header("Content-Type", this.rewriter.contentType())
                    .body(this.rewriter.rewrite(rewritten))
                    .build();
            });
    }

    /**
     * 403 response for "every version blocked" — Go-client-parseable text.
     */
    private Response allBlockedResponse(final String module) {
        EcsLogger.info("com.auto1.pantera.go")
            .message("@latest has no non-blocked fallback — returning 403")
            .eventCategory("web")
            .eventAction("latest_filter")
            .eventOutcome("failure")
            .field("event.reason", "all_versions_blocked")
            .field("repository.name", this.repoName)
            .field("package.name", module)
            .log();
        return ResponseBuilder.forbidden()
            .header("X-Pantera-Cooldown", "all-blocked")
            .textBody(
                "All versions of '" + module
                    + "' are under cooldown; no fallback available."
            )
            .build();
    }

    /**
     * Parse a newline-delimited {@code @v/list} body into versions.
     * Mirrors {@code GoMetadataParser.parse()} but is local to avoid a
     * hard dependency on the SPI parser when it evolves independently.
     */
    private List<String> parseVersionList(final byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        final String body = new String(bytes, StandardCharsets.UTF_8);
        final String[] lines = body.split("\n", -1);
        final List<String> out = new ArrayList<>(lines.length);
        for (final String line : lines) {
            final String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /**
     * Evaluate every candidate for cooldown and return the highest
     * non-blocked version by Go's semver ordering. Evaluation is capped
     * at {@link #MAX_VERSIONS_TO_EVALUATE} newest candidates.
     */
    private CompletableFuture<java.util.Optional<String>> pickHighestNonBlocked(
        final List<String> candidates, final String module, final String user
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return CompletableFuture.completedFuture(java.util.Optional.empty());
        }
        // Sort descending (newest first) per Go semver semantics.
        final Comparator<String> semverDesc = VersionComparators.semver().reversed();
        final List<String> sorted = new ArrayList<>(candidates);
        sorted.sort(semverDesc);
        final List<String> bounded = sorted.size() > MAX_VERSIONS_TO_EVALUATE
            ? sorted.subList(0, MAX_VERSIONS_TO_EVALUATE)
            : sorted;
        // Evaluate each candidate in parallel, then pick the newest non-blocked.
        final List<CompletableFuture<Boolean>> futures = new ArrayList<>(bounded.size());
        for (final String version : bounded) {
            futures.add(this.isBlocked(module, version, user));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                for (int idx = 0; idx < bounded.size(); idx++) {
                    if (!futures.get(idx).join()) {
                        return java.util.Optional.of(bounded.get(idx));
                    }
                }
                return java.util.Optional.<String>empty();
            });
    }

    /**
     * Ask the cooldown service whether {@code version} is currently
     * blocked for this repo. Swallows per-version evaluation errors by
     * treating them as "not blocked" — a transient inspector failure
     * must not cascade to denying every Go resolution.
     */
    private CompletableFuture<Boolean> isBlocked(
        final String module, final String version, final String user
    ) {
        final CooldownRequest req = new CooldownRequest(
            this.repoType,
            this.repoName,
            module,
            version,
            user == null ? "go-latest" : user,
            Instant.now()
        );
        return this.cooldown.evaluate(req, this.inspector)
            .thenApply(result -> result.blocked())
            .exceptionally(err -> {
                EcsLogger.warn("com.auto1.pantera.go")
                    .message("Cooldown evaluation failed; treating version as allowed")
                    .eventCategory("database")
                    .eventAction("cooldown_evaluate")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("package.name", module)
                    .field("package.version", version)
                    .error(err)
                    .log();
                return false;
            });
    }

    /**
     * Drain a reactive-streams body to a byte array.
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
