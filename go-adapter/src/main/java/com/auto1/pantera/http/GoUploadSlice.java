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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Concatenation;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.http.cache.NegativeCacheRegistry;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.index.SyncArtifactIndexer;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;
import com.auto1.pantera.http.slice.ContentWithSize;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.scheduling.ArtifactEvent;

import hu.akarnokd.rxjava2.interop.SingleInterop;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Go repository upload slice. Handles uploads of module artifacts and emits metadata events.
 *
 * @since 1.0
 */
final class GoUploadSlice implements Slice {

    /**
     * Repository type identifier for metadata events.
     */
    private static final String REPO_TYPE = "go";

    /**
     * Path pattern for Go module artifacts.
     * Matches: /module/path/@v/v1.2.3.{info|mod|zip}
     */
    private static final Pattern ARTIFACT = Pattern.compile(
        "^/?(?<module>.+)/@v/v(?<version>[^/]+)\\.(?<ext>info|mod|zip)$"
    );

    /**
     * Repository storage.
     */
    private final Storage storage;

    /**
     * Optional metadata events queue.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String repo;

    /**
     * Synchronous artifact-index writer. Runs inline with upload so the
     * group resolver's index lookup sees the new artifact immediately on
     * the very next request — no stale-index window. Defaults to
     * {@link SyncArtifactIndexer#NOOP} when no DataSource is wired (e.g.
     * tests, file-only deployments). The async event queue continues to
     * fire for audit / metrics regardless.
     */
    private final SyncArtifactIndexer syncIndex;

    /**
     * New Go upload slice (legacy ctor — no synchronous index writer).
     *
     * @param storage Repository storage
     * @param repo Repository name
     * @param events Metadata events queue
     */
    GoUploadSlice(
        final Storage storage,
        final String repo,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this(storage, repo, events, SyncArtifactIndexer.NOOP);
    }

    /**
     * New Go upload slice with synchronous index writer.
     *
     * @param storage Repository storage
     * @param repo Repository name
     * @param events Metadata events queue
     * @param syncIndex Synchronous artifact-index writer
     */
    GoUploadSlice(
        final Storage storage,
        final String repo,
        final Optional<Queue<ArtifactEvent>> events,
        final SyncArtifactIndexer syncIndex
    ) {
        this.storage = storage;
        this.repo = repo;
        this.events = events;
        this.syncIndex = syncIndex;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // Strip semicolon-separated metadata properties from the path to avoid exceeding
        // filesystem filename length limits (typically 255 bytes). These properties are
        // added by build tools (e.g., vcs.revision, build.timestamp)
        // but are not part of the actual module filename.
        final String path = line.uri().getPath();
        final String sanitizedPath;
        final int semicolonIndex = path.indexOf(';');
        if (semicolonIndex > 0) {
            sanitizedPath = path.substring(0, semicolonIndex);
            EcsLogger.debug("com.auto1.pantera.go")
                .message("Stripped metadata properties from path")
                .eventCategory("web")
                .eventAction("upload")
                .field("url.original", path)
                .field("url.path", sanitizedPath)
                .log();
        } else {
            sanitizedPath = path;
        }

        final Key key = new KeyFromPath(sanitizedPath);
        final Matcher matcher = ARTIFACT.matcher(normalise(sanitizedPath));
        final CompletableFuture<Void> stored = this.storage.save(
            key,
            new ContentWithSize(body, headers)
        );
        CompletableFuture<Void> extra;
        if (matcher.matches()) {
            final String module = matcher.group("module");
            final String version = matcher.group("version");
            final String ext = matcher.group("ext").toLowerCase(Locale.ROOT);
            if ("zip".equals(ext)) {
                extra = stored.thenCompose(
                    nothing -> this.recordEvent(headers, module, version, key)
                ).thenCompose(
                    nothing -> this.updateList(module, version)
                );
            } else {
                extra = stored;
            }
            // Invalidate any negative-cache 404s recorded for this module
            // (or its parent paths, e.g. Go's parent-path probing) BEFORE
            // we tell the client the upload succeeded. Otherwise an earlier
            // probe-against-group that cached a 404 keeps shadowing the
            // newly-published artifact and `go get` returns 404.
            extra = extra.whenComplete((ignored, error) -> {
                if (error != null) {
                    return;
                }
                try {
                    final int n = NegativeCacheRegistry.instance().sharedCache()
                        .invalidateByArtifactName(module);
                    if (n > 0) {
                        EcsLogger.info("com.auto1.pantera.go")
                            .message("Negative-cache invalidated after upload "
                                + "(module=" + module + ", invalidated=" + n + ")")
                            .eventCategory("database")
                            .eventAction("neg_cache_invalidate_on_upload")
                            .field("package.name", module)
                            .log();
                    }
                } catch (final RuntimeException ex) {
                    EcsLogger.warn("com.auto1.pantera.go")
                        .message("Negative-cache invalidation after upload failed")
                        .error(ex)
                        .log();
                }
            });
        } else {
            extra = stored;
        }
        return extra.thenApply(ignored -> ResponseBuilder.created().build());
    }

    /**
     * Record artifact upload after the binary is stored.
     *
     * <p>Writes the artifact-index row synchronously (so the next group
     * resolver lookup sees the new artifact immediately) AND publishes an
     * async {@link ArtifactEvent} to the queue (so audit logging, metrics
     * and any other async consumers still fire). The two writers target the
     * same DB row via idempotent UPSERT so they cannot diverge.
     *
     * @param headers Request headers
     * @param module Module path
     * @param version Module version (without leading `v`)
     * @param key Storage key for uploaded artifact
     * @return Completion stage that completes when the synchronous index
     *         write has landed
     */
    private CompletableFuture<Void> recordEvent(
        final Headers headers,
        final String module,
        final String version,
        final Key key
    ) {
        return this.storage.metadata(key)
            .thenApply(meta -> meta.read(Meta.OP_SIZE).orElseThrow())
            .thenCompose(size -> {
                final ArtifactEvent event = new ArtifactEvent(
                    REPO_TYPE, this.repo, owner(headers),
                    module, version, size
                );
                this.events.ifPresent(
                    queue -> queue.add( // ok: unbounded ConcurrentLinkedDeque
                        event
                    )
                );
                return this.syncIndex.recordSync(event);
            });
    }

    /**
     * Update {@code list} file with provided module version.
     *
     * @param module Module path
     * @param version Module version (without leading `v`)
     * @return Completion stage
     */
    private CompletableFuture<Void> updateList(
        final String module,
        final String version
    ) {
        final Key list = new Key.From(String.format("%s/@v/list", module));
        final String entry = String.format("v%s", version);
        return this.storage.exists(list).thenCompose(
            exists -> {
                if (!exists) {
                    return this.storage.save(
                        list,
                        new Content.From((entry + '\n').getBytes(StandardCharsets.UTF_8))
                    );
                }
                return this.storage.value(list).thenCompose(
                    content -> {
                        // OPTIMIZATION: Use size hint for efficient pre-allocation
                        final long knownSize = content.size().orElse(-1L);
                        return Concatenation.withSize(content, knownSize).single()
                            .map(Remaining::new)
                            .map(Remaining::bytes)
                            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                            .to(SingleInterop.get())
                            .thenCompose(existing -> {
                                final LinkedHashSet<String> versions = new LinkedHashSet<>();
                                existing.lines()
                                    .map(String::trim)
                                    .filter(line -> !line.isEmpty())
                                    .forEach(versions::add);
                                if (!versions.add(entry)) {
                                    return CompletableFuture.completedFuture(null);
                                }
                                final String updated = String.join("\n", versions) + '\n';
                                return this.storage.save(
                                    list,
                                    new Content.From(updated.getBytes(StandardCharsets.UTF_8))
                                );
                            });
                    }
                );
            }
        );
    }

    /**
     * Extract owner from request headers.
     *
     * @param headers Request headers
     * @return Owner name or default value
     */
    private static String owner(final Headers headers) {
        final String value = new Login(headers).getValue();
        if (value == null || value.isBlank()) {
            return ArtifactEvent.DEF_OWNER;
        }
        return value;
    }

    /**
     * Remove leading slash if present.
     *
     * @param path Request path
     * @return Normalised path
     */
    private static String normalise(final String path) {
        if (path.isEmpty()) {
            return path;
        }
        return path.charAt(0) == '/' ? path.substring(1) : path;
    }
}
