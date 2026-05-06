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
package com.auto1.pantera.composer.cooldown;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.metadata.MetadataParseException;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Orchestrates cooldown-aware filtering of Composer per-package metadata
 * responses — {@code /packages/<vendor>/<pkg>.json} (v1-style) and
 * {@code /p2/<vendor>/<pkg>.json} (Composer v2 lazy-provider endpoint).
 *
 * <p>This handler is where {@link ComposerMetadataParser},
 * {@link ComposerMetadataFilter}, and {@link ComposerMetadataRequestDetector}
 * — the trio registered via the cooldown SPI in {@code CooldownWiring} —
 * are actually consumed on the serve path. Prior to this class the
 * registered {@code composerBundle} was dead infrastructure: the proxy
 * slice's {@code CachedProxySlice} checked cooldown only against the
 * single "latest by timestamp" version rather than running each
 * candidate through the filter, so blocked versions leaked to
 * {@code composer require vendor/pkg} resolving against the full
 * version map.</p>
 *
 * <p>Mirrors the Go handler ({@code GoListHandler} — {@code 1eb53ceb}),
 * PyPI handler ({@code PypiSimpleHandler} — {@code 19bc60cb}) and
 * Docker handler ({@code DockerTagsListHandler} — {@code 6c5a30ef}).
 * The dispatch order in {@code ComposerProxySlice} must route
 * per-package metadata requests through this class before the generic
 * {@code CachedProxySlice} upstream-fetch flow.</p>
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>Fetch {@code /packages/<vendor>/<pkg>.json} or
 *       {@code /p2/<vendor>/<pkg>.json} from upstream via the shared
 *       slice (same auth / cache / resilience layers as archive
 *       downloads).</li>
 *   <li>On non-2xx, forward status + body unchanged — never transform
 *       upstream errors.</li>
 *   <li>Parse the JSON via {@link ComposerMetadataParser}. On parse
 *       failure, pass upstream bytes through unchanged.</li>
 *   <li>Evaluate every declared version against cooldown in parallel;
 *       collect the blocked set.</li>
 *   <li>Run {@link ComposerMetadataFilter#filter} to drop blocked
 *       entries from the version map and re-serialise as JSON.</li>
 *   <li>If every version is blocked → 404, which is composer's
 *       convention for "package unavailable" and triggers its usual
 *       error path cleanly. Matches the Go/PyPI convention.</li>
 * </ol>
 *
 * @since 2.2.0
 */
public final class ComposerPackageMetadataHandler {

    /**
     * Composer metadata content type.
     */
    private static final String CONTENT_TYPE = "application/json";

    /**
     * Shared Jackson mapper. Thread-safe after construction.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Upstream slice shared with the main Composer proxy so the same
     * auth / cache / resilience layers apply to the metadata fetch.
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
     * Repository type (e.g. {@code "php"}, {@code "php-proxy"}).
     */
    private final String repoType;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Path detector for the per-package metadata endpoints.
     */
    private final ComposerMetadataRequestDetector detector;

    /**
     * Parser for Composer per-package metadata JSON.
     */
    private final ComposerMetadataParser parser;

    /**
     * Pure filter that drops blocked versions from the version map.
     */
    private final ComposerMetadataFilter filter;

    /**
     * Ctor.
     *
     * @param upstream Upstream Composer proxy slice
     * @param cooldown Cooldown evaluation service
     * @param inspector Cooldown inspector
     * @param repoType Repository type (e.g. {@code "php"})
     * @param repoName Repository name
     */
    public ComposerPackageMetadataHandler(
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
        this.detector = new ComposerMetadataRequestDetector();
        this.parser = new ComposerMetadataParser();
        this.filter = new ComposerMetadataFilter();
    }

    /**
     * Whether this handler should intercept the given request path.
     *
     * @param path Request path
     * @return true for {@code /packages/<vendor>/<pkg>.json} or
     *     {@code /p2/<vendor>/<pkg>.json} paths with a parseable
     *     vendor/package name.
     */
    public boolean matches(final String path) {
        return this.detector.isMetadataRequest(path)
            && this.detector.extractPackageName(path).isPresent();
    }

    /**
     * Handle a per-package metadata request with cooldown filtering.
     *
     * @param line Request line (must be a per-package metadata path)
     * @param user Authenticated user (for cooldown bookkeeping)
     * @return Future response
     */
    public CompletableFuture<Response> handle(final RequestLine line, final String user) {
        final String path = line.uri().getPath();
        final String pkg = this.detector.extractPackageName(path).orElseThrow(
            () -> new IllegalArgumentException("Not a Composer metadata path: " + path)
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
        final JsonNode parsed;
        try {
            parsed = this.parser.parse(upstreamBytes);
        } catch (final MetadataParseException ex) {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Failed to parse per-package metadata JSON — passing upstream bytes through")
                .eventCategory("web")
                .eventAction("metadata_filter")
                .eventOutcome("success")
                .field("event.reason", "upstream_malformed")
                .field("repository.name", this.repoName)
                .field("package.name", pkg)
                .error(ex)
                .log();
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Type", CONTENT_TYPE)
                    .body(upstreamBytes)
                    .build()
            );
        }
        final List<String> versions = this.parser.extractVersions(parsed);
        if (versions.isEmpty()) {
            // Empty version map — nothing to filter. Serve upstream
            // bytes verbatim so formatting / ordering round-trip.
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Type", CONTENT_TYPE)
                    .body(upstreamBytes)
                    .build()
            );
        }
        return this.blockedVersions(pkg, versions, user).thenApply(blocked -> {
            if (blocked.isEmpty()) {
                // Fast path: forward upstream bytes verbatim.
                return ResponseBuilder.ok()
                    .header("Content-Type", CONTENT_TYPE)
                    .body(upstreamBytes)
                    .build();
            }
            final JsonNode filtered = this.filter.filter(parsed, blocked);
            final List<String> kept = this.parser.extractVersions(filtered);
            if (kept.isEmpty()) {
                return this.allBlockedResponse(pkg);
            }
            try {
                final byte[] body = MAPPER.writeValueAsBytes(filtered);
                EcsLogger.info("com.auto1.pantera.composer")
                    .message("Composer metadata filtered: removed cooldown-blocked versions"
                        + " (total=" + versions.size()
                        + ", blocked=" + blocked.size()
                        + ", served=" + kept.size() + ")")
                    .eventCategory("web")
                    .eventAction("metadata_filter")
                    .eventOutcome("success")
                    .field("repository.name", this.repoName)
                    .field("package.name", pkg)
                    .log();
                return ResponseBuilder.ok()
                    .header("Content-Type", CONTENT_TYPE)
                    .body(body)
                    .build();
            } catch (final com.fasterxml.jackson.core.JsonProcessingException ex) {
                EcsLogger.warn("com.auto1.pantera.composer")
                    .message("Composer metadata re-serialisation failed — falling back to upstream body")
                    .eventCategory("web")
                    .eventAction("metadata_filter")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("package.name", pkg)
                    .error(ex)
                    .log();
                return ResponseBuilder.ok()
                    .header("Content-Type", CONTENT_TYPE)
                    .body(upstreamBytes)
                    .build();
            }
        });
    }

    /**
     * Every version blocked — 404. Composer handles 404 on metadata
     * endpoints as "package unavailable" and surfaces a clean error
     * to the operator.
     */
    private Response allBlockedResponse(final String pkg) {
        EcsLogger.info("com.auto1.pantera.composer")
            .message("Per-package metadata has no non-blocked versions — returning 404")
            .eventCategory("web")
            .eventAction("metadata_filter")
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
     * transient inspector failure never denies an entire response.
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

    private CompletableFuture<Boolean> isBlocked(
        final String pkg, final String version, final String user
    ) {
        final CooldownRequest req = new CooldownRequest(
            this.repoType,
            this.repoName,
            pkg,
            version,
            user == null ? "composer-metadata" : user,
            Instant.now()
        );
        return this.cooldown.evaluate(req, this.inspector)
            .thenApply(result -> result.blocked())
            .exceptionally(err -> {
                EcsLogger.warn("com.auto1.pantera.composer")
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
     * in the Go / PyPI / Docker cooldown handlers.
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
