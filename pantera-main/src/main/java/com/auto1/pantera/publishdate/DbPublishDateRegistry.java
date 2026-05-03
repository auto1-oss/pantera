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
import com.auto1.pantera.metrics.MicrometerMetrics;
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
    /**
     * Short-TTL cache of recent fetch failures. Avoids hammering a slow or
     * unreachable upstream registry once we know it's not answering for a
     * given (repoType, name, version). Sentinel-only: presence means "we
     * just tried and failed, don't try again for {@link #NEGATIVE_TTL}".
     */
    private final Cache<CacheKey, Boolean> negativeL1;
    private static final java.time.Duration NEGATIVE_TTL = java.time.Duration.ofSeconds(60);

    /**
     * In-flight upstream lookups, keyed by {@link CacheKey}. Coalesces
     * concurrent {@link #publishDate} calls for the same key into one
     * upstream HTTP request: the first caller starts the fetch, subsequent
     * callers attach to the same future.
     *
     * <p>Without this, a {@code go get} fanout that probes the same module
     * version from multiple goroutines (transitive resolution probes parent
     * paths and version aliases) fires N redundant requests to
     * proxy.golang.org. The upstream throttles at ~100 concurrent and our
     * own per-request timeout (1500ms) fires before any of them complete —
     * everything fails open and we never populate the L1/L2 cache.
     *
     * <p>Entries are removed from this map on completion (success or
     * failure) so memory cannot grow unbounded.
     */
    private final java.util.concurrent.ConcurrentMap<CacheKey,
        CompletableFuture<Optional<Instant>>> inFlight =
            new java.util.concurrent.ConcurrentHashMap<>();

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
        this.negativeL1 = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(NEGATIVE_TTL)
            .recordStats()
            .build();
    }

    @Override
    public CompletableFuture<Optional<Instant>> publishDate(
        final String repoType, final String name, final String version
    ) {
        final long startNanos = System.nanoTime();
        final CacheKey key = new CacheKey(repoType, name, version);
        final Instant l1hit = this.l1.getIfPresent(key);
        if (l1hit != null) {
            recordOutcome(repoType, "l1_hit", startNanos);
            return CompletableFuture.completedFuture(Optional.of(l1hit));
        }
        // Recent fetch failure within negative TTL — short-circuit, don't
        // retry the upstream fetch. Lets cooldown fail-open instantly when
        // the registry is degraded and avoids retry storms.
        if (this.negativeL1.getIfPresent(key) != null) {
            recordOutcome(repoType, "negative_hit", startNanos);
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.supplyAsync(() -> readDb(key))
            .thenCompose(dbHit -> {
                if (dbHit.isPresent()) {
                    this.l1.put(key, dbHit.get());
                    recordOutcome(repoType, "l2_hit", startNanos);
                    return CompletableFuture.completedFuture(dbHit);
                }
                final PublishDateSource src = this.sourcesByRepoType.get(repoType);
                if (src == null) {
                    recordOutcome(repoType, "source_miss", startNanos);
                    return CompletableFuture.completedFuture(Optional.<Instant>empty());
                }
                return coalescedFetch(key, src, name, version, repoType, startNanos);
            });
    }

    /**
     * Issue one upstream fetch per (repoType, name, version) at a time.
     * Subsequent concurrent callers receive the in-flight future, avoiding
     * redundant HTTP calls that would otherwise trigger upstream rate-limits
     * during high-fanout dependency resolution.
     *
     * <p>Uses {@code putIfAbsent} (not {@code computeIfAbsent}) because the
     * cleanup callback removes the same entry — that would be a "recursive
     * update" inside {@code computeIfAbsent} and ConcurrentHashMap rejects
     * those at runtime.
     */
    private CompletableFuture<Optional<Instant>> coalescedFetch(
        final CacheKey key, final PublishDateSource src,
        final String name, final String version,
        final String repoType, final long startNanos
    ) {
        final CompletableFuture<Optional<Instant>> ours = new CompletableFuture<>();
        final CompletableFuture<Optional<Instant>> existing =
            this.inFlight.putIfAbsent(key, ours);
        if (existing != null) {
            return existing;
        }
        // We won the slot — fire the actual fetch and pipe the outcome
        // into `ours`. The in-flight map entry is cleared on completion
        // (success, miss, or exception) so future callers re-fetch.
        src.fetch(name, version)
            .thenApply(opt -> {
                if (opt.isPresent()) {
                    writeDb(key, opt.get(), src.sourceId());
                    this.l1.put(key, opt.get());
                    recordOutcome(repoType, "source_hit", startNanos);
                } else {
                    this.negativeL1.put(key, Boolean.TRUE);
                    recordOutcome(repoType, "source_miss", startNanos);
                }
                return opt;
            })
            .exceptionally(err -> {
                this.negativeL1.put(key, Boolean.TRUE);
                recordOutcome(repoType, "source_error", startNanos);
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
                return Optional.<Instant>empty();
            })
            .whenComplete((opt, err) -> {
                this.inFlight.remove(key);
                if (err != null) {
                    ours.completeExceptionally(err);
                } else {
                    ours.complete(opt);
                }
            });
        return ours;
    }

    private static void recordOutcome(final String repoType, final String outcome, final long startNanos) {
        if (MicrometerMetrics.isInitialized()) {
            final long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            MicrometerMetrics.getInstance().recordPublishDateLookup(repoType, outcome, durationMs);
        }
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

    public void persist(
        final String repoType, final String name, final String version,
        final Instant when, final String source
    ) {
        final CacheKey key = new CacheKey(repoType, name, version);
        this.l1.put(key, when);
        this.negativeL1.invalidate(key);
        writeDb(key, when, source);
    }

    private record CacheKey(String repoType, String name, String version) { }
}
