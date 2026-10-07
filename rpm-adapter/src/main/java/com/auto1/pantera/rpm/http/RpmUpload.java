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
package com.auto1.pantera.rpm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.lock.storage.IndexUpdateLock;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.rpm.RepoConfig;
import com.auto1.pantera.rpm.asto.AstoRepoAdd;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.google.common.base.Splitter;
import com.google.common.collect.Streams;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Slice for rpm packages upload.
 */
public final class RpmUpload implements Slice {

    /**
     * Temp key for the packages to remove.
     */
    public static final Key TO_ADD = new Key.From(".add");

    /**
     * Repository type.
     */
    static final String REPO_TYPE = "rpm";

    /**
     * Asto storage.
     */
    private final Storage asto;

    /**
     * Repo config.
     */
    private final RepoConfig config;

    /**
     * Pantera artifact upload/remove events.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * RPM repository HTTP API.
     *
     * @param storage Storage
     * @param config Repository configuration
     * @param events Pantera artifact upload/remove events
     */
    RpmUpload(final Storage storage, final RepoConfig config,
        final Optional<Queue<ArtifactEvent>> events) {
        this(storage, config, events, com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /** Synchronous artifact-index writer. */
    private final com.auto1.pantera.index.SyncArtifactIndexer syncIndex;

    /**
     * Whether an existing package may never be replaced, not even with
     * {@code ?override=true}.
     */
    private final boolean immutable;

    /**
     * Ctor with synchronous index writer; an existing package is replaced
     * only with {@code ?override=true}.
     */
    RpmUpload(final Storage storage, final RepoConfig config,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex) {
        this(storage, config, events, syncIndex, false);
    }

    /**
     * Primary ctor.
     *
     * @param storage Storage
     * @param config Repository configuration
     * @param events Pantera artifact upload/remove events
     * @param syncIndex Synchronous artifact-index writer
     * @param immutable Whether an existing package may never be replaced:
     *  {@code true} answers 409 even with {@code ?override=true}; {@code false}
     *  replaces it when the client sends {@code ?override=true}
     */
    RpmUpload(final Storage storage, final RepoConfig config,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
        final boolean immutable) {
        this.asto = storage;
        this.config = config;
        this.events = events;
        this.syncIndex = syncIndex;
        this.immutable = immutable;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers,
        final Content body) {
        final Request request = new Request(line);
        final Key key = request.file();
        final Key pending = new Key.From(RpmUpload.TO_ADD, key);
        // The existence check and the staging write run under one lock on
        // the package, kept in storage, so two uploads of the same package,
        // here or on another instance sharing the storage, cannot both pass
        // the check; a package staged but not yet moved into place counts.
        final AtomicBoolean started = new AtomicBoolean();
        return new IndexUpdateLock(this.asto, key).run(
            locked -> {
                started.set(true);
                final CompletionStage<Boolean> conflict;
                if (request.override() && !this.immutable) {
                    conflict = CompletableFuture.completedFuture(false);
                } else {
                    conflict = locked.exists(key).thenCombine(
                        locked.exists(pending), (stored, staged) -> stored || staged
                    );
                }
                return conflict.thenCompose(
                    conflicts -> {
                        final CompletionStage<Boolean> staged;
                        if (conflicts) {
                            // Drain the refused upload so its buffers are released.
                            staged = body.discard().handle((ignored, err) -> false);
                        } else {
                            staged = locked.save(pending, new Content.From(body))
                                .thenApply(ignored -> true);
                        }
                        return staged;
                    }
                );
            }
        ).exceptionallyCompose(
            err -> {
                // The lock was never acquired: nothing read the body yet.
                final CompletableFuture<Void> drained = started.get()
                    ? CompletableFuture.completedFuture(null)
                    : body.discard().toCompletableFuture();
                return drained.thenCompose(ignored -> CompletableFuture.failedFuture(err));
            }
        ).thenCompose(
                staged -> {
                    final CompletionStage<RsStatus> status;
                    if (!staged) {
                        status = CompletableFuture.completedFuture(RsStatus.CONFLICT);
                    } else {
                        status = CompletableFuture.completedFuture(null).thenCompose(
                            ignored -> {
                                final CompletionStage<Void> result;
                                if (request.skipUpdate()
                                    || this.config.mode() == RepoConfig.UpdateMode.CRON) {
                                    result = CompletableFuture.allOf();
                                } else {
                                    final AstoRepoAdd repo =
                                        new AstoRepoAdd(this.asto, this.config);
                                    result = new RepodataQueue(this.asto)
                                        .run(repo::performWithResult).thenCompose(list -> {
                                        final java.util.List<CompletableFuture<Void>> syncs =
                                            new java.util.ArrayList<>();
                                        list.forEach(info -> {
                                            final ArtifactEvent event = new ArtifactEvent(
                                                RpmUpload.REPO_TYPE, this.config.name(),
                                                new Login(headers).getValue(),
                                                info.name(), info.version(),
                                                info.packageSize(),
                                                System.currentTimeMillis(), null,
                                                info.packagePath()
                                            ).withRequestContext(headers);
                                            this.events.ifPresent(queue -> queue.add(event));
                                            syncs.add(this.syncIndex.recordSync(event));
                                            com.auto1.pantera.http.cache.NegativeCacheRegistry
                                                .instance()
                                                .invalidateAfterUpload("rpm", info.name());
                                            com.auto1.pantera.cooldown.metadata
                                                .FilteredMetadataCacheRegistry.instance()
                                                .invalidateAfterUpload("rpm", info.name());
                                        });
                                        return CompletableFuture.allOf(
                                            syncs.toArray(CompletableFuture[]::new)
                                        );
                                    });
                                }
                                return result;
                            }
                        ).thenApply(nothing -> RsStatus.ACCEPTED);
                    }
                    return status;
                }
            ).thenApply(s -> ResponseBuilder.from(s).build())
            .toCompletableFuture();
    }

    /**
     * Request line.
     *
     * @since 0.9
     */
    static final class Request {

        /**
         * RegEx pattern for path.
         */
        public static final Pattern PTRN = Pattern.compile("^/(?<rpm>.*\\.rpm)");

        /**
         * Request line.
         */
        private final RequestLine line;

        /**
         * Ctor.
         *
         * @param line Line from request
         */
        Request(final RequestLine line) {
            this.line = line;
        }

        /**
         * Returns file key.
         *
         * @return File key
         */
        public Key file() {
            return new Key.From(this.path().group("rpm"));
        }

        /**
         * Returns override param.
         *
         * @return Override param value, <code>false</code> - if absent
         */
        public boolean override() {
            return this.hasParamValue("override=true");
        }

        /**
         * Returns `skip_update` param.
         *
         * @return Skip update param value, <code>false</code> - if absent
         */
        public boolean skipUpdate() {
            return this.hasParamValue("skip_update=true");
        }

        /**
         * Returns `force` param.
         *
         * @return Force param value, <code>false</code> - if absent
         */
        public boolean force() {
            return this.hasParamValue("force=true");
        }

        /**
         * Matches request path by RegEx pattern.
         *
         * @return Path matcher.
         */
        private Matcher path() {
            final String path = this.line.uri().getPath();
            final Matcher matcher = PTRN.matcher(path);
            if (!matcher.matches()) {
                throw new IllegalStateException(String.format("Unexpected path: %s", path));
            }
            return matcher;
        }

        /**
         * Checks that request query contains param with value.
         *
         * @param param Param with value string.
         * @return Result is <code>true</code> if there is param with value,
         *  <code>false</code> - otherwise.
         */
        private boolean hasParamValue(final String param) {
            return Optional.ofNullable(this.line.uri().getQuery())
                .map(query -> Streams.stream(Splitter.on("&").split(query)))
                .orElse(Stream.empty())
                .anyMatch(part -> part.equals(param));
        }
    }
}
