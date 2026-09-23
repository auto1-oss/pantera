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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.ext.Digests;
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

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
     * Serializes publishes of the same version file and rewrites of the same
     * {@code @v/list} across every Go repository slice in this JVM.
     */
    private static final KeyedSerializer SERIAL = new KeyedSerializer();

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
            EcsLogger.debug("com.auto1.pantera.http")
                .message("Stripped metadata properties from path")
                .eventCategory("web")
                .eventAction("upload")
                .field("url.original", path)
                .field("url.path", sanitizedPath)
                .field("log.source", "application")
                .log();
        } else {
            sanitizedPath = path;
        }

        final Key key = new KeyFromPath(sanitizedPath);
        final Matcher matcher = ARTIFACT.matcher(normalise(sanitizedPath));
        if (!matcher.matches()) {
            return this.storage.save(key, new ContentWithSize(body, headers))
                .thenApply(ignored -> ResponseBuilder.created().build());
        }
        final String module = matcher.group("module");
        final String version = matcher.group("version");
        final boolean zip = "zip".equals(matcher.group("ext").toLowerCase(Locale.ROOT));
        return SERIAL.run(
            this.repo + '|' + key.string(),
            () -> this.store(key, headers, body)
        ).thenCompose(
            outcome -> {
                if (outcome == Outcome.CONFLICT) {
                    return CompletableFuture.completedFuture(this.conflict(sanitizedPath));
                }
                return this.published(outcome, headers, module, version, key, zip)
                    .thenApply(ignored -> ResponseBuilder.created().build());
            }
        );
    }

    /**
     * Store an artifact unless that version file is already published.
     *
     * <p>Go module versions are immutable: consumers pin the hashes of the
     * {@code .mod} and {@code .zip} in {@code go.sum}, so replacing a
     * published file breaks every build that already resolved it with a
     * {@code SECURITY ERROR}. A re-upload of byte-identical content is
     * accepted as an idempotent retry; different content is a conflict and
     * the published bytes are kept.</p>
     *
     * @param key Storage key
     * @param headers Request headers
     * @param body Request body
     * @return Outcome
     */
    private CompletableFuture<Outcome> store(
        final Key key, final Headers headers, final Content body
    ) {
        return this.storage.exists(key).thenCompose(
            exists -> {
                if (exists) {
                    return this.compare(key, body);
                }
                return this.storage.save(key, new ContentWithSize(body, headers))
                    .thenApply(ignored -> Outcome.STORED);
            }
        );
    }

    /**
     * Compare an upload with the stored file (consumes the upload body).
     * @param key Storage key
     * @param body Uploaded body
     * @return IDENTICAL or CONFLICT
     */
    private CompletableFuture<Outcome> compare(final Key key, final Content body) {
        return new ContentDigest(body, Digests.SHA256).hex().toCompletableFuture()
            .thenCompose(
                uploaded -> this.storage.value(key).thenCompose(
                    stored -> new ContentDigest(stored, Digests.SHA256).hex()
                ).thenApply(
                    existing -> {
                        if (existing.equals(uploaded)) {
                            return Outcome.IDENTICAL;
                        }
                        return Outcome.CONFLICT;
                    }
                )
            );
    }

    /**
     * Post-publish steps: record the event (first publish of a zip only),
     * update {@code @v/list} for a zip, invalidate negative caches.
     * @param outcome Store outcome
     * @param headers Request headers
     * @param module Module path
     * @param version Version without leading {@code v}
     * @param key Storage key
     * @param zip Whether the file is the module zip
     * @return Completion
     */
    private CompletableFuture<Void> published(
        final Outcome outcome, final Headers headers, final String module,
        final String version, final Key key, final boolean zip
    ) {
        CompletableFuture<Void> extra = CompletableFuture.completedFuture(null);
        if (zip) {
            if (outcome == Outcome.STORED) {
                extra = this.recordEvent(headers, module, version, key);
            }
            extra = extra.thenCompose(nothing -> this.updateList(module));
        }
        // Invalidate any negative-cache 404s recorded for this module
        // (or its parent paths, e.g. Go's parent-path probing) BEFORE
        // we tell the client the upload succeeded. Otherwise an earlier
        // probe-against-group that cached a 404 keeps shadowing the
        // newly-published artifact and `go get` returns 404.
        return extra.whenComplete((ignored, error) -> {
            if (error == null) {
                NegativeCacheRegistry.instance()
                    .invalidateAfterUpload("go-proxy", module);
                // Group 404s are keyed by the real module path.
                final String real = new com.auto1.pantera.goproxy.ModulePath(module).decoded();
                if (!real.equals(module)) {
                    NegativeCacheRegistry.instance().invalidateAfterUpload("go-proxy", real);
                }
                com.auto1.pantera.cooldown.metadata
                    .FilteredMetadataCacheRegistry.instance()
                    .invalidateAfterUpload("go-proxy", module);
            }
        });
    }

    /**
     * Log and build the 409 answer for a republish with different content.
     * @param path Request path
     * @return Response
     */
    private Response conflict(final String path) {
        EcsLogger.warn("com.auto1.pantera.http")
            .message("Rejected republish of an existing Go module version with different content")
            .eventCategory("web")
            .eventAction("artifact_publish")
            .eventOutcome("failure")
            .field("event.reason", "version_immutable")
            .field("repository.name", this.repo)
            .field("url.path", path)
            .field("log.source", "application")
            .log();
        return ResponseBuilder.from(RsStatus.CONFLICT)
            .textBody(
                "This module version is already published with different content. "
                    + "Go module versions are immutable; publish a new version instead."
            ).build();
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
                // Real module path ("!b" -> "B"): what search and group
                // routing look the module up by; the key stays escaped.
                final ArtifactEvent event = new ArtifactEvent(
                    REPO_TYPE, this.repo, owner(headers),
                    new com.auto1.pantera.goproxy.ModulePath(module).decoded(), version, size,
                    System.currentTimeMillis(), null, key.string()
                ).withRequestContext(headers);
                this.events.ifPresent(
                    queue -> queue.add( // ok: unbounded ConcurrentLinkedDeque
                        event
                    )
                );
                return this.syncIndex.recordSync(event);
            });
    }

    /**
     * Rewrite the module's {@code @v/list}, one rewrite at a time per module.
     *
     * <p>The list keeps its existing entries and gains every version whose
     * {@code .zip} is in storage, so a version is listed even when an
     * earlier rewrite was lost (another cluster node, or a list written
     * before rewrites were serialized).</p>
     *
     * @param module Module path
     * @return Completion stage
     */
    private CompletableFuture<Void> updateList(final String module) {
        final Key list = new Key.From(String.format("%s/@v/list", module));
        return SERIAL.run(
            this.repo + '|' + list.string(),
            () -> this.readList(list).thenCompose(
                existing -> this.storage.list(new Key.From(String.format("%s/@v", module)))
                    .thenCompose(keys -> this.writeList(list, existing, keys))
            )
        );
    }

    /**
     * Read the current {@code @v/list} entries.
     * @param list List key
     * @return Entries in file order, empty if the list does not exist
     */
    private CompletableFuture<List<String>> readList(final Key list) {
        return this.storage.exists(list).thenCompose(
            exists -> {
                if (!exists) {
                    return CompletableFuture.completedFuture(List.<String>of());
                }
                return this.storage.value(list)
                    .thenCompose(Content::asStringFuture)
                    .thenApply(
                        text -> text.lines().map(String::trim)
                            .filter(line -> !line.isEmpty())
                            .collect(Collectors.toList())
                    );
            }
        );
    }

    /**
     * Save {@code @v/list} if stored zips add versions to it.
     * @param list List key
     * @param existing Current entries
     * @param keys Keys under the module's {@code @v} directory
     * @return Completion stage
     */
    private CompletableFuture<Void> writeList(
        final Key list, final List<String> existing, final Collection<Key> keys
    ) {
        final String prefix = list.parent().map(Key::string).orElse("") + '/';
        final List<String> stored = keys.stream()
            .map(Key::string)
            .filter(name -> name.startsWith(prefix) && name.endsWith(".zip"))
            .map(name -> name.substring(prefix.length(), name.length() - ".zip".length()))
            .filter(name -> name.startsWith("v") && name.indexOf('/') < 0)
            .sorted(new GoVersionOrder())
            .collect(Collectors.toList());
        final LinkedHashSet<String> versions = new LinkedHashSet<>(existing);
        versions.addAll(stored);
        if (versions.size() == existing.size()) {
            return CompletableFuture.completedFuture(null);
        }
        final String updated = String.join("\n", versions) + '\n';
        return this.storage.save(
            list, new Content.From(updated.getBytes(StandardCharsets.UTF_8))
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

    /**
     * Outcome of storing an uploaded version file.
     */
    private enum Outcome {
        /**
         * First publish: stored.
         */
        STORED,
        /**
         * Same bytes already published: nothing stored.
         */
        IDENTICAL,
        /**
         * Different bytes already published: rejected.
         */
        CONFLICT
    }
}
