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
package com.auto1.pantera.publishdate;

import com.auto1.pantera.http.log.EcsLogger;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Default {@link PublishDateRegistry}: Caffeine L1 + Postgres L2 + per-ecosystem
 * {@link PublishDateSource} fallback. Rows are immutable; absence is not cached.
 *
 * @since 2.2.0
 */
public final class DbPublishDateRegistry implements PublishDateRegistry {

    private static final String SELECT_SQL =
        "SELECT published_at FROM artifact_publish_dates "
        + "WHERE repo_type = ? AND name = ? AND version = ?";

    private static final String INSERT_SQL =
        "INSERT INTO artifact_publish_dates (repo_type, name, version, published_at, source) "
        + "VALUES (?, ?, ?, ?, ?) "
        + "ON CONFLICT (repo_type, name, version) DO NOTHING";

    private final DataSource ds;
    private final Map<String, PublishDateSource> sourcesByRepoType;
    private final Cache<CacheKey, Instant> l1;

    public DbPublishDateRegistry(
        final DataSource ds,
        final Map<String, PublishDateSource> sourcesByRepoType
    ) {
        this.ds = ds;
        this.sourcesByRepoType = Map.copyOf(sourcesByRepoType);
        this.l1 = Caffeine.newBuilder()
            .maximumSize(100_000)
            .recordStats()
            .build();
    }

    @Override
    public CompletableFuture<Optional<Instant>> publishDate(
        final String repoType, final String name, final String version
    ) {
        final CacheKey key = new CacheKey(repoType, name, version);
        final Instant l1hit = this.l1.getIfPresent(key);
        if (l1hit != null) {
            return CompletableFuture.completedFuture(Optional.of(l1hit));
        }
        return CompletableFuture.supplyAsync(() -> readDb(key))
            .thenCompose(dbHit -> {
                if (dbHit.isPresent()) {
                    this.l1.put(key, dbHit.get());
                    return CompletableFuture.completedFuture(dbHit);
                }
                final PublishDateSource src = this.sourcesByRepoType.get(repoType);
                if (src == null) {
                    return CompletableFuture.completedFuture(Optional.<Instant>empty());
                }
                return src.fetch(name, version)
                    .thenApply(opt -> {
                        if (opt.isPresent()) {
                            writeDb(key, opt.get(), src.sourceId());
                            this.l1.put(key, opt.get());
                        }
                        return opt;
                    })
                    .exceptionally(err -> {
                        EcsLogger.warn("com.auto1.pantera.publishdate")
                            .message("Source fetch failed for "
                                + repoType + " " + name + "@" + version)
                            .eventCategory("network")
                            .eventAction("publish_date_fetch")
                            .eventOutcome("failure")
                            .field("repository.type", repoType)
                            .field("package.name", name)
                            .field("package.version", version)
                            .error(err)
                            .log();
                        return Optional.empty();
                    });
            });
    }

    private Optional<Instant> readDb(final CacheKey key) {
        try (Connection conn = this.ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SQL)) {
            ps.setString(1, key.repoType);
            ps.setString(2, key.name);
            ps.setString(3, key.version);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getTimestamp(1).toInstant());
                }
            }
        } catch (Exception ex) {
            EcsLogger.warn("com.auto1.pantera.publishdate")
                .message("DB read failed for publish_date")
                .eventCategory("database")
                .eventAction("publish_date_read")
                .eventOutcome("failure")
                .error(ex)
                .log();
        }
        return Optional.empty();
    }

    private void writeDb(final CacheKey key, final Instant when, final String source) {
        try (Connection conn = this.ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            ps.setString(1, key.repoType);
            ps.setString(2, key.name);
            ps.setString(3, key.version);
            ps.setTimestamp(4, Timestamp.from(when));
            ps.setString(5, source);
            ps.executeUpdate();
        } catch (Exception ex) {
            EcsLogger.warn("com.auto1.pantera.publishdate")
                .message("DB write failed for publish_date — value still served from L1")
                .eventCategory("database")
                .eventAction("publish_date_write")
                .eventOutcome("failure")
                .error(ex)
                .log();
        }
    }

    private record CacheKey(String repoType, String name, String version) { }
}
