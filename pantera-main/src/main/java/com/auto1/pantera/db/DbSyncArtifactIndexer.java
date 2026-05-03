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
package com.auto1.pantera.db;

import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.index.SyncArtifactIndexer;
import com.auto1.pantera.scheduling.ArtifactEvent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;

/**
 * Postgres implementation of {@link SyncArtifactIndexer}. Issues a single
 * UPSERT against the {@code artifacts} table per event, keeping the index
 * consistent with hosted storage at upload-completion time.
 *
 * <p>SQL must stay byte-for-byte aligned with the batched UPSERT in
 * {@code DbConsumer.DbObserver.onNext} so the two writers cannot disagree
 * on column semantics.
 *
 * @since 2.2.0
 */
public final class DbSyncArtifactIndexer implements SyncArtifactIndexer {

    /**
     * UPSERT SQL — must match the statement used in {@code DbConsumer}.
     */
    private static final String UPSERT_SQL =
        "INSERT INTO artifacts (repo_type, repo_name, name, version, size, "
        + "created_date, release_date, owner, path_prefix) "
        + "VALUES (?,?,?,?,?,?,?,?,?) "
        + "ON CONFLICT (repo_name, name, version) "
        + "DO UPDATE SET repo_type = EXCLUDED.repo_type, size = EXCLUDED.size, "
        + "created_date = EXCLUDED.created_date, release_date = EXCLUDED.release_date, "
        + "owner = EXCLUDED.owner, "
        + "path_prefix = COALESCE(EXCLUDED.path_prefix, artifacts.path_prefix)";

    private final DataSource dataSource;

    public DbSyncArtifactIndexer(final DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public CompletableFuture<Void> recordSync(final ArtifactEvent event) {
        if (event == null
            || event.eventType() != ArtifactEvent.Type.INSERT) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(
            () -> upsert(event), HandlerExecutor.get()
        ).exceptionally(err -> {
            // Fall through silently — the async DbConsumer path is still
            // running and will retry on its own batch cycle. The failure
            // here only delays read-after-write consistency, it doesn't
            // lose the event.
            EcsLogger.warn("com.auto1.pantera.db")
                .message("Synchronous artifact index UPSERT failed; "
                    + "async batch path will retry")
                .eventCategory("database")
                .eventAction("artifact_index_sync_upsert")
                .eventOutcome("failure")
                .field("repository.name", event.repoName())
                .field("package.name", event.artifactName())
                .field("package.version", event.artifactVersion())
                .error(err)
                .log();
            return null;
        });
    }

    private void upsert(final ArtifactEvent event) {
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(UPSERT_SQL)) {
            final long release = event.releaseDate().orElse(event.createdDate());
            stmt.setString(1, event.repoType());
            stmt.setString(2, normalize(event.repoName()));
            stmt.setString(3, event.artifactName());
            stmt.setString(4, event.artifactVersion());
            stmt.setDouble(5, event.size());
            stmt.setLong(6, event.createdDate());
            stmt.setLong(7, release);
            stmt.setString(8, event.owner());
            stmt.setString(9, event.pathPrefix());
            stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException(
                "Synchronous artifact index UPSERT failed", err
            );
        }
    }

    private static String normalize(final String name) {
        return name == null ? null : name.trim();
    }
}
