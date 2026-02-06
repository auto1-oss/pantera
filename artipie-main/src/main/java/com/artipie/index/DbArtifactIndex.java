/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.index;

import com.artipie.http.log.EcsLogger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PostgreSQL-backed implementation of {@link ArtifactIndex}.
 * Uses JDBC queries against the existing {@code artifacts} table.
 * <p>
 * This implementation is always "warmed up" since the database is the
 * authoritative source and is always consistent. No warmup scan is needed.
 *
 * @since 1.20.13
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidCatchingGenericException"})
public final class DbArtifactIndex implements ArtifactIndex {

    /**
     * UPSERT SQL — same pattern as DbConsumer.
     */
    private static final String UPSERT_SQL = String.join(
        " ",
        "INSERT INTO artifacts",
        "(repo_type, repo_name, name, version, size, created_date, release_date, owner, path_prefix)",
        "VALUES (?,?,?,?,?,?,?,?,?)",
        "ON CONFLICT (repo_name, name, version)",
        "DO UPDATE SET repo_type = EXCLUDED.repo_type, size = EXCLUDED.size,",
        "created_date = EXCLUDED.created_date, release_date = EXCLUDED.release_date,",
        "owner = EXCLUDED.owner, path_prefix = COALESCE(EXCLUDED.path_prefix, artifacts.path_prefix)"
    );

    /**
     * DELETE by repo and name.
     */
    private static final String DELETE_SQL =
        "DELETE FROM artifacts WHERE repo_name = ? AND name = ?";

    /**
     * Full-text search SQL using tsvector/GIN index with relevance ranking.
     * Falls back to LIKE if tsvector returns no results.
     */
    private static final String FTS_SEARCH_SQL = String.join(
        " ",
        "SELECT repo_type, repo_name, name, version, size, created_date, owner,",
        "ts_rank(search_tokens, plainto_tsquery('simple', ?)) AS rank",
        "FROM artifacts WHERE search_tokens @@ plainto_tsquery('simple', ?)",
        "ORDER BY rank DESC, name, version LIMIT ? OFFSET ?"
    );

    /**
     * Full-text count SQL using tsvector.
     */
    private static final String FTS_COUNT_SQL =
        "SELECT COUNT(*) FROM artifacts WHERE search_tokens @@ plainto_tsquery('simple', ?)";

    /**
     * Fallback search SQL with LIKE (used when tsvector is unavailable or returns 0 results).
     */
    private static final String LIKE_SEARCH_SQL = String.join(
        " ",
        "SELECT repo_type, repo_name, name, version, size, created_date, owner",
        "FROM artifacts WHERE LOWER(name) LIKE LOWER(?)",
        "ORDER BY name, version LIMIT ? OFFSET ?"
    );

    /**
     * Fallback count SQL with LIKE.
     */
    private static final String LIKE_COUNT_SQL =
        "SELECT COUNT(*) FROM artifacts WHERE LOWER(name) LIKE LOWER(?)";

    /**
     * Locate SQL — finds repos containing an artifact by matching URL path
     * against stored path prefixes (for proxy artifacts) or exact name match
     * (for locally published artifacts).
     */
    private static final String LOCATE_SQL = String.join(" ",
        "SELECT DISTINCT repo_name FROM artifacts",
        "WHERE (path_prefix IS NOT NULL AND ? LIKE path_prefix || '/%')",
        "OR name = ?"
    );

    /**
     * Total count SQL.
     */
    private static final String TOTAL_COUNT_SQL = "SELECT COUNT(*) FROM artifacts";

    /**
     * Thread counter for executor threads.
     */
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);

    /**
     * JDBC DataSource.
     */
    private final DataSource source;

    /**
     * Executor for async operations.
     */
    private final ExecutorService executor;

    /**
     * Whether the executor was created internally (and should be shut down on close).
     */
    private final boolean ownedExecutor;

    /**
     * Constructor with default executor.
     * Creates a fixed thread pool sized to available processors.
     *
     * @param source JDBC DataSource
     */
    public DbArtifactIndex(final DataSource source) {
        this(
            source,
            Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                r -> {
                    final Thread thread = new Thread(
                        r,
                        "db-artifact-index-" + THREAD_COUNTER.incrementAndGet()
                    );
                    thread.setDaemon(true);
                    return thread;
                }
            ),
            true
        );
    }

    /**
     * Constructor with explicit executor.
     *
     * @param source JDBC DataSource
     * @param executor Executor for async operations
     */
    public DbArtifactIndex(final DataSource source, final ExecutorService executor) {
        this(source, executor, false);
    }

    /**
     * Internal constructor.
     *
     * @param source JDBC DataSource
     * @param executor Executor for async operations
     * @param ownedExecutor Whether the executor is owned by this instance
     */
    private DbArtifactIndex(
        final DataSource source,
        final ExecutorService executor,
        final boolean ownedExecutor
    ) {
        this.source = Objects.requireNonNull(source, "DataSource must not be null");
        this.executor = Objects.requireNonNull(executor, "ExecutorService must not be null");
        this.ownedExecutor = ownedExecutor;
    }

    @Override
    public CompletableFuture<Void> index(final ArtifactDocument doc) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = this.source.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(UPSERT_SQL)) {
                setUpsertParams(stmt, doc);
                stmt.executeUpdate();
            } catch (final SQLException ex) {
                EcsLogger.error("com.artipie.index")
                    .message("Failed to index artifact")
                    .eventCategory("index")
                    .eventAction("db_index")
                    .eventOutcome("failure")
                    .field("package.name", doc.artifactPath())
                    .error(ex)
                    .log();
                throw new RuntimeException("Failed to index artifact: " + doc.artifactPath(), ex);
            }
        }, this.executor);
    }

    @Override
    public CompletableFuture<Void> remove(final String repoName, final String artifactPath) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = this.source.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(DELETE_SQL)) {
                stmt.setString(1, repoName);
                stmt.setString(2, artifactPath);
                stmt.executeUpdate();
            } catch (final SQLException ex) {
                EcsLogger.error("com.artipie.index")
                    .message("Failed to remove artifact")
                    .eventCategory("index")
                    .eventAction("db_remove")
                    .eventOutcome("failure")
                    .field("repository.name", repoName)
                    .field("package.name", artifactPath)
                    .error(ex)
                    .log();
                throw new RuntimeException(
                    String.format("Failed to remove artifact %s from %s", artifactPath, repoName),
                    ex
                );
            }
        }, this.executor);
    }

    @Override
    public CompletableFuture<SearchResult> search(
        final String query, final int maxResults, final int offset
    ) {
        return CompletableFuture.supplyAsync(() -> {
            // If query contains SQL wildcards, use LIKE directly
            final boolean uselike = query.contains("%") || query.contains("_");
            if (uselike) {
                return DbArtifactIndex.searchWithLike(
                    this.source, query, maxResults, offset
                );
            }
            // Try tsvector full-text search first
            try {
                final SearchResult ftsResult = DbArtifactIndex.searchWithFts(
                    this.source, query, maxResults, offset
                );
                // Fall back to LIKE if tsvector returned zero results
                // (handles substring matches that tsvector misses)
                if (ftsResult.totalHits() == 0) {
                    return DbArtifactIndex.searchWithLike(
                        this.source, "%" + query + "%", maxResults, offset
                    );
                }
                return ftsResult;
            } catch (final SQLException ex) {
                // Graceful degradation: if tsvector column doesn't exist or
                // any FTS-related error occurs, fall back to LIKE
                EcsLogger.warn("com.artipie.index")
                    .message("FTS search failed, falling back to LIKE: " + ex.getMessage())
                    .eventCategory("search")
                    .eventAction("db_fts_fallback")
                    .error(ex)
                    .log();
                return DbArtifactIndex.searchWithLike(
                    this.source, "%" + query + "%", maxResults, offset
                );
            }
        }, this.executor);
    }

    /**
     * Execute full-text search using tsvector/GIN index.
     *
     * @param source DataSource
     * @param query Search query (plain text, not a pattern)
     * @param maxResults Max results per page
     * @param offset Pagination offset
     * @return SearchResult with ranked results
     * @throws SQLException On database error (caller should handle for fallback)
     */
    private static SearchResult searchWithFts(
        final DataSource source, final String query,
        final int maxResults, final int offset
    ) throws SQLException {
        final long totalHits;
        final List<ArtifactDocument> docs = new ArrayList<>();
        try (Connection conn = source.getConnection()) {
            // Get total count using FTS
            try (PreparedStatement countStmt = conn.prepareStatement(FTS_COUNT_SQL)) {
                countStmt.setString(1, query);
                try (ResultSet rs = countStmt.executeQuery()) {
                    rs.next();
                    totalHits = rs.getLong(1);
                }
            }
            // Get paginated results with relevance ranking
            try (PreparedStatement stmt = conn.prepareStatement(FTS_SEARCH_SQL)) {
                stmt.setString(1, query);
                stmt.setString(2, query);
                stmt.setInt(3, maxResults);
                stmt.setInt(4, offset);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        docs.add(fromResultSet(rs));
                    }
                }
            }
        }
        return new SearchResult(docs, totalHits, offset, null);
    }

    /**
     * Execute search using LIKE pattern matching (fallback).
     *
     * @param source DataSource
     * @param pattern LIKE pattern (should include % wildcards)
     * @param maxResults Max results per page
     * @param offset Pagination offset
     * @return SearchResult
     */
    private static SearchResult searchWithLike(
        final DataSource source, final String pattern,
        final int maxResults, final int offset
    ) {
        final long totalHits;
        final List<ArtifactDocument> docs = new ArrayList<>();
        try (Connection conn = source.getConnection()) {
            // Get total count
            try (PreparedStatement countStmt = conn.prepareStatement(LIKE_COUNT_SQL)) {
                countStmt.setString(1, pattern);
                try (ResultSet rs = countStmt.executeQuery()) {
                    rs.next();
                    totalHits = rs.getLong(1);
                }
            }
            // Get paginated results
            try (PreparedStatement stmt = conn.prepareStatement(LIKE_SEARCH_SQL)) {
                stmt.setString(1, pattern);
                stmt.setInt(2, maxResults);
                stmt.setInt(3, offset);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        docs.add(fromResultSet(rs));
                    }
                }
            }
        } catch (final SQLException ex) {
            EcsLogger.error("com.artipie.index")
                .message("LIKE search failed for pattern: " + pattern)
                .eventCategory("search")
                .eventAction("db_search_like")
                .eventOutcome("failure")
                .error(ex)
                .log();
            return SearchResult.EMPTY;
        }
        return new SearchResult(docs, totalHits, offset, null);
    }

    @Override
    public CompletableFuture<List<String>> locate(final String artifactPath) {
        return CompletableFuture.supplyAsync(() -> {
            final List<String> repos = new ArrayList<>();
            try (Connection conn = this.source.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(LOCATE_SQL)) {
                stmt.setString(1, artifactPath);
                stmt.setString(2, artifactPath);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        repos.add(rs.getString("repo_name"));
                    }
                }
            } catch (final SQLException ex) {
                EcsLogger.error("com.artipie.index")
                    .message("Locate failed for path: " + artifactPath)
                    .eventCategory("search")
                    .eventAction("db_locate")
                    .eventOutcome("failure")
                    .error(ex)
                    .log();
                return List.of();
            }
            return repos;
        }, this.executor);
    }

    @Override
    public boolean isWarmedUp() {
        return true;
    }

    @Override
    public void setWarmedUp() {
        // No-op: DB-backed index is always consistent
    }

    @Override
    public CompletableFuture<Map<String, Object>> getStats() {
        return CompletableFuture.supplyAsync(() -> {
            final Map<String, Object> stats = new HashMap<>(3);
            try (Connection conn = this.source.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(TOTAL_COUNT_SQL);
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                stats.put("documents", rs.getLong(1));
            } catch (final SQLException ex) {
                EcsLogger.error("com.artipie.index")
                    .message("Failed to get index stats")
                    .eventCategory("index")
                    .eventAction("db_stats")
                    .eventOutcome("failure")
                    .error(ex)
                    .log();
                stats.put("documents", -1L);
            }
            stats.put("warmedUp", true);
            stats.put("type", "postgresql");
            stats.put("searchEngine", "tsvector/GIN");
            return stats;
        }, this.executor);
    }

    @Override
    public CompletableFuture<Void> indexBatch(final Collection<ArtifactDocument> docs) {
        if (docs.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = this.source.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(UPSERT_SQL)) {
                conn.setAutoCommit(false);
                for (final ArtifactDocument doc : docs) {
                    setUpsertParams(stmt, doc);
                    stmt.addBatch();
                }
                stmt.executeBatch();
                conn.commit();
            } catch (final SQLException ex) {
                EcsLogger.error("com.artipie.index")
                    .message("Failed to batch index " + docs.size() + " artifacts")
                    .eventCategory("index")
                    .eventAction("db_index_batch")
                    .eventOutcome("failure")
                    .error(ex)
                    .log();
                throw new RuntimeException(
                    "Failed to batch index " + docs.size() + " artifacts", ex
                );
            }
        }, this.executor);
    }

    @Override
    public void close() {
        if (this.ownedExecutor) {
            this.executor.shutdown();
            try {
                if (!this.executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    this.executor.shutdownNow();
                }
            } catch (final InterruptedException ex) {
                this.executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Set UPSERT prepared statement parameters from an ArtifactDocument.
     *
     * @param stmt Prepared statement
     * @param doc Artifact document
     * @throws SQLException On SQL error
     */
    private static void setUpsertParams(
        final PreparedStatement stmt, final ArtifactDocument doc
    ) throws SQLException {
        stmt.setString(1, doc.repoType());
        stmt.setString(2, doc.repoName());
        stmt.setString(3, doc.artifactPath());
        stmt.setString(4, doc.version() != null ? doc.version() : "");
        stmt.setLong(5, doc.size());
        final long createdEpoch = doc.createdAt() != null
            ? doc.createdAt().toEpochMilli()
            : System.currentTimeMillis();
        stmt.setLong(6, createdEpoch);
        stmt.setLong(7, createdEpoch);
        stmt.setString(8, doc.owner() != null ? doc.owner() : "");
        stmt.setString(9, null); // path_prefix populated by DbConsumer from ArtifactEvent
    }

    /**
     * Convert a ResultSet row to an ArtifactDocument.
     *
     * @param rs ResultSet positioned at a row
     * @return ArtifactDocument
     * @throws SQLException On SQL error
     */
    private static ArtifactDocument fromResultSet(final ResultSet rs) throws SQLException {
        final String name = rs.getString("name");
        final long createdDate = rs.getLong("created_date");
        return new ArtifactDocument(
            rs.getString("repo_type"),
            rs.getString("repo_name"),
            name,
            name,
            rs.getString("version"),
            rs.getLong("size"),
            Instant.ofEpochMilli(createdDate),
            rs.getString("owner")
        );
    }
}
