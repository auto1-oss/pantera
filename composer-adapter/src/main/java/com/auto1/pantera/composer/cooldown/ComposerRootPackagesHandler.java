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
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;

import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates cooldown-aware filtering of Composer root aggregation
 * responses — {@code /packages.json} and {@code /repo.json}.
 *
 * <p>This handler closes the root-aggregation gap: if a repository's
 * root metadata inlines the full {@code packages} map (Satis
 * snapshots, small private repos), {@code composer require
 * vendor/pkg} will resolve against that inline map rather than
 * following the lazy-providers URL. Without filtering here, blocked
 * versions would leak through the root response even though the
 * per-package {@code /p2/<vendor>/<pkg>.json} endpoint is filtered.</p>
 *
 * <p>Mirrors the Go handler ({@code GoListHandler} — {@code 1eb53ceb}),
 * PyPI handler ({@code PypiSimpleHandler} / {@code PypiJsonHandler} —
 * {@code 19bc60cb}) and Docker handler ({@code DockerTagsListHandler} —
 * {@code 6c5a30ef}). The dispatch order in {@code ComposerProxySlice}
 * must route root-metadata requests through this class before the
 * generic upstream-fetch flow.</p>
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>Fetch {@code /packages.json} or {@code /repo.json} from
 *       upstream via the shared slice.</li>
 *   <li>On non-2xx, forward status + body unchanged.</li>
 *   <li>Parse as JSON. On parse failure, pass upstream bytes through
 *       unchanged.</li>
 *   <li>If the root uses the lazy-providers / metadata-url scheme
 *       (no inline {@code packages} version data), return upstream
 *       bytes verbatim — per-package filtering handles it.</li>
 *   <li>For inline shapes, collect every
 *       {@code (package, version)} pair and evaluate each against
 *       cooldown in parallel.</li>
 *   <li>Run {@link ComposerRootPackagesFilter#filter} with the
 *       collected blocked set; re-serialise as JSON.</li>
 *   <li>Root aggregations always return 200 — even when every
 *       package is blocked — because the root <em>shape</em> is
 *       always valid with an empty {@code packages}, and a 404 at
 *       the repository root would confuse Composer clients more
 *       than an empty aggregation.</li>
 * </ol>
 *
 * @since 2.2.0
 */
public final class ComposerRootPackagesHandler {

    /**
     * Composer metadata content type.
     */
    private static final String CONTENT_TYPE = "application/json";

    /**
     * Shared Jackson mapper.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Upstream slice shared with the main Composer proxy.
     */
    private final Slice upstream;

    /**
     * Cooldown evaluation service.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown inspector.
     */
    private final CooldownInspector inspector;

    /**
     * Repository type.
     */
    private final String repoType;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Path detector for root aggregation endpoints.
     */
    private final ComposerRootPackagesRequestDetector detector;

    /**
     * Root aggregation filter.
     */
    private final ComposerRootPackagesFilter filter;

    /**
     * Ctor.
     *
     * @param upstream Upstream Composer proxy slice
     * @param cooldown Cooldown evaluation service
     * @param inspector Cooldown inspector
     * @param repoType Repository type (e.g. {@code "php"})
     * @param repoName Repository name
     */
    public ComposerRootPackagesHandler(
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
        this.detector = new ComposerRootPackagesRequestDetector();
        this.filter = new ComposerRootPackagesFilter();
    }

    /**
     * Whether this handler should intercept the given path.
     *
     * @param path Request path
     * @return true for {@code /packages.json} or {@code /repo.json}
     */
    public boolean matches(final String path) {
        return this.detector.isMetadataRequest(path);
    }

    /**
     * Handle a root aggregation request with cooldown filtering.
     *
     * @param line Request line
     * @param user Authenticated user (for cooldown bookkeeping)
     * @return Future response
     */
    public CompletableFuture<Response> handle(final RequestLine line, final String user) {
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
                    this.processUpstream(bytes, user)
                );
            });
    }

    /**
     * Parse → detect inline vs. lazy-provider → collect version pairs
     * → evaluate cooldown → filter → serialise.
     */
    private CompletableFuture<Response> processUpstream(
        final byte[] upstreamBytes, final String user
    ) {
        final JsonNode parsed;
        try {
            parsed = MAPPER.readTree(upstreamBytes);
        } catch (final java.io.IOException ex) {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Failed to parse root packages JSON — passing upstream bytes through")
                .eventCategory("web")
                .eventAction("root_filter")
                .eventOutcome("success")
                .field("event.reason", "upstream_malformed")
                .field("repository.name", this.repoName)
                .error(ex)
                .log();
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Type", CONTENT_TYPE)
                    .body(upstreamBytes)
                    .build()
            );
        }
        if (parsed == null) {
            try {
                throw new MetadataParseException("Parsed root metadata is null");
            } catch (final MetadataParseException ex) {
                EcsLogger.warn("com.auto1.pantera.composer")
                    .message("Root metadata parsed to null — passing upstream bytes through")
                    .eventCategory("web")
                    .eventAction("root_filter")
                    .eventOutcome("success")
                    .field("repository.name", this.repoName)
                    .error(ex)
                    .log();
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Type", CONTENT_TYPE)
                    .body(upstreamBytes)
                    .build()
            );
        }
        final List<ComposerRootPackagesFilter.PackageVersion> entries =
            this.filter.extractPackageVersions(parsed);
        if (entries.isEmpty()) {
            // Lazy-providers / metadata-url scheme, or empty packages.
            // Per-package filtering via ComposerPackageMetadataHandler
            // handles the actual version-map lookups. Serve upstream
            // bytes verbatim to preserve exact top-level field ordering.
            EcsLogger.debug("com.auto1.pantera.composer")
                .message("Root packages: lazy-providers scheme, no inline versions")
                .eventCategory("web")
                .eventAction("root_filter")
                .eventOutcome("success")
                .field("repository.name", this.repoName)
                .log();
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Type", CONTENT_TYPE)
                    .body(upstreamBytes)
                    .build()
            );
        }
        return this.blockedVersions(entries, user).thenApply(blocked -> {
            if (blocked.isEmpty()) {
                // Nothing blocked; forward upstream bytes verbatim.
                return ResponseBuilder.ok()
                    .header("Content-Type", CONTENT_TYPE)
                    .body(upstreamBytes)
                    .build();
            }
            final JsonNode filtered = this.filter.filter(
                parsed,
                (pkg, ver) -> {
                    final Set<String> blockedForPkg = blocked.get(pkg);
                    return blockedForPkg != null && blockedForPkg.contains(ver);
                }
            );
            try {
                final byte[] body = MAPPER.writeValueAsBytes(filtered);
                EcsLogger.info("com.auto1.pantera.composer")
                    .message("Root packages filtered: removed cooldown-blocked versions"
                        + " (total=" + entries.size()
                        + ", blocked=" + blocked.size() + ")")
                    .eventCategory("web")
                    .eventAction("root_filter")
                    .eventOutcome("success")
                    .field("repository.name", this.repoName)
                    .log();
                return ResponseBuilder.ok()
                    .header("Content-Type", CONTENT_TYPE)
                    .body(body)
                    .build();
            } catch (final com.fasterxml.jackson.core.JsonProcessingException ex) {
                EcsLogger.warn("com.auto1.pantera.composer")
                    .message("Root packages re-serialisation failed — falling back to upstream body")
                    .eventCategory("web")
                    .eventAction("root_filter")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
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
     * Evaluate every candidate (pkg, version) against cooldown in
     * parallel; return a {@code pkg -> blocked-versions} map.
     */
    private CompletableFuture<Map<String, Set<String>>> blockedVersions(
        final List<ComposerRootPackagesFilter.PackageVersion> candidates,
        final String user
    ) {
        final List<CompletableFuture<Boolean>> futures =
            new ArrayList<>(candidates.size());
        for (final ComposerRootPackagesFilter.PackageVersion pv : candidates) {
            futures.add(this.isBlocked(pv.pkg(), pv.version(), user));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                final Map<String, Set<String>> blocked = new HashMap<>();
                for (int idx = 0; idx < candidates.size(); idx++) {
                    if (futures.get(idx).join()) {
                        final ComposerRootPackagesFilter.PackageVersion pv =
                            candidates.get(idx);
                        blocked
                            .computeIfAbsent(pv.pkg(), k -> new HashSet<>())
                            .add(pv.version());
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
            user == null ? "composer-root" : user,
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
     * Drain a reactive-streams body to a byte array. Mirrors helpers
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
