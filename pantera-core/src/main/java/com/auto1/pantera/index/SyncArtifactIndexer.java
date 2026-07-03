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
package com.auto1.pantera.index;

import com.auto1.pantera.scheduling.ArtifactEvent;
import java.util.concurrent.CompletableFuture;

/**
 * Synchronously persists an {@link ArtifactEvent} to the artifact index so a
 * subsequent group-resolver lookup can see the new artifact.
 *
 * <p>Pantera's primary index update path is asynchronous (events are batched
 * by {@code DbConsumer} every 2 s / 200 events), which is excellent for
 * throughput and audit but creates a stale-index window after an upload —
 * the artifact is in storage immediately but the index lookup misses for up
 * to 2 s. During that window, group resolvers that trust the index (the
 * proxy-only-fanout optimisation, on a par with JFrog/Nexus) will skip the
 * hosted member and 404 the request, then cache that 404.
 *
 * <p>Upload slices that need read-after-write consistency (Go, Maven, npm,
 * PyPI…) call {@link #recordSync(ArtifactEvent)} inline before composing
 * their {@code 201 Created} response. The async event queue is still used
 * for the rest of the publish-side effects (audit log, metrics).
 *
 * @since 2.2.0
 */
public interface SyncArtifactIndexer {

    /** No-op indexer used in tests and when no DB is available. */
    SyncArtifactIndexer NOOP = event -> CompletableFuture.completedFuture(null);

    /**
     * Insert (or upsert) the artifact described by {@code event} into the
     * artifact index now. The returned future completes when the index row
     * is durable in the underlying store; the caller should wait on it
     * before responding to its client.
     *
     * @param event Artifact event describing the just-uploaded artifact
     * @return future that completes once the index entry is persisted
     */
    CompletableFuture<Void> recordSync(ArtifactEvent event);
}
