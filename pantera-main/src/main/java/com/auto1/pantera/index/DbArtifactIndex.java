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

import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.misc.ConfigDefaults;

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
     * Uses COUNT(*) OVER() window function to get the total hit count in a
     * single index scan instead of a separate COUNT query.
     */
    private static final String FTS_SEARCH_SQL = String.join(
        " ",
        "SELECT repo_type, repo_name, name, version, size, created_date, owner,",
        "ts_rank(search_tokens, plainto_tsquery('simple', ?)) AS rank,",
        "COUNT(*) OVER() AS total_count",
        "FROM artifacts WHERE search_tokens @@ plainto_tsquery('simple', ?)",
        "ORDER BY rank DESC, name, version LIMIT ? OFFSET ?"
    );

    /**
     * Prefix-matching FTS SQL using to_tsquery with ':*' suffix.
     * Matches words starting with query terms: "test" matches "test", "testing", etc.
     * Uses COUNT(*) OVER() window function to avoid a separate COUNT query.
     */
    private static final String PREFIX_FTS_SEARCH_SQL = String.join(
        " ",
        "SELECT repo_type, repo_name, name, version, size, created_date, owner,",
        "ts_rank(search_tokens, to_tsquery('simple', ?)) AS rank,",
        "COUNT(*) OVER() AS total_count",
        "FROM artifacts WHERE search_tokens @@ to_tsquery('simple', ?)",
        "ORDER BY rank DESC, name, version LIMIT ? OFFSET ?"
    );

    /**
     * Fallback search SQL with LIKE (used when tsvector is unavailable or returns 0 results).
     * Uses COUNT(*) OVER() window function to avoid a separate COUNT query.
     */
    private static final String LIKE_SEARCH_SQL = String.join(
        " ",
        "SELECT repo_type, repo_name, name, version, size, created_date, owner,",
        "COUNT(*) OVER() AS total_count",
        "FROM artifacts WHERE LOWER(name) LIKE LOWER(?)",
        "ORDER BY name, version LIMIT ? OFFSET ?"
    );

    /**
     * Statement timeout for LIKE fallback queries.
     * Configurable via PANTERA_SEARCH_LIKE_TIMEOUT_MS env var.
     * Prevents runaway full-table scans from consuming the connection pool.
     */
    private static final long LIKE_TIMEOUT_MS =
        ConfigDefaults.getLong("PANTERA_SEARCH_LIKE_TIMEOUT_MS", 3000L);

    /**
     * Locate SQL suffix — exact name match for locally published artifacts.
     * The full query is built dynamically by {@link #buildLocateSql(int)}
     * to include an IN clause with path prefix candidates.
     */
    private static final String LOCATE_NAME_CLAUSE = " OR name = ?";

    /**
     * Locate SQL prefix — finds repos by matching decomposed path prefixes.
     * Uses IN (?, ?, ...) for index-friendly equality lookups instead of
     * reverse LIKE which forces a full table scan.
     */
    private static final String LOCATE_PREFIX =
        "SELECT DISTINCT repo_name FROM artifacts WHERE path_prefix IN (";

    /**
     * Locate by name SQL — fast indexed lookup on the {@code name} column.
     * Uses idx_artifacts_locate (name, repo_name) for O(log n) performance.
     */
    private static final String LOCATE_BY_NAME_SQL =
        "SELECT DISTINCT repo_name FROM artifacts WHERE name = ?";

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
        this.warmUp();
    }

    /**
     * Eagerly warm executor threads and JDBC connection so the first real
     * request doesn't pay the ~100ms cold-start penalty.
     */
    private void warmUp() {
        this.executor.execute(() -> {
            try (Connection conn = this.source.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("SELECT 1")) {
                stmt.executeQuery().close();
            } catch (final SQLException ex) {
                // Non-fatal — first real request will pay the cost instead
            }
        });
    }

    @Override
    public CompletableFuture<Void> index(final ArtifactDocument doc) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = this.source.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(UPSERT_SQL)) {
                setUpsertParams(stmt, doc);
                stmt.executeUpdate();
            } catch (final SQLException ex) {
                EcsLogger.error("com.auto1.pantera.index")
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
                EcsLogger.error("com.auto1.pantera.index")
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
        final String query, final int maxResults, final int offset,
        final String repoType, final String repoName, final String sortBy, final boolean sortAsc
    ) {
        return CompletableFuture.supplyAsync(() -> {
            final boolean uselike = query.contains("%") || query.contains("_");
            if (uselike) {
                return DbArtifactIndex.searchFilteredLike(
                    this.source, query, maxResults, offset, repoType, repoName, sortBy, sortAsc
                );
            }
            try {
                final SearchResult ftsResult = DbArtifactIndex.searchFilteredPrefixFts(
                    this.source, query, maxResults, offset, repoType, repoName, sortBy, sortAsc
                );
                if (ftsResult.totalHits() == 0) {
                    final SearchResult exact = DbArtifactIndex.searchFilteredFts(
                        this.source, query, maxResults, offset, repoType, repoName, sortBy, sortAsc
                    );
                    if (exact.totalHits() == 0) {
                        return DbArtifactIndex.searchFilteredLike(
                            this.source, "%" + query + "%", maxResults, offset,
                            repoType, repoName, sortBy, sortAsc
                        );
                    }
                    return exact;
                }
                return ftsResult;
            } catch (final SQLException ex) {
                EcsLogger.warn("com.auto1.pantera.index")
                    .message("FTS search failed, falling back to LIKE: " + ex.getMessage())
                    .eventCategory("search")
                    .eventAction("db_fts_fallback")
                    .error(ex)
                    .log();
                return DbArtifactIndex.searchFilteredLike(
                    this.source, "%" + query + "%", maxResults, offset,
                    repoType, repoName, sortBy, sortAsc
                );
            }
        }, this.executor);
    }

    /**
     * Build the ORDER BY clause based on sort parameters.
     * For version sort, uses integer array comparison for natural ordering
     * (4.1, 4.2, ..., 4.9, 4.10) instead of lexicographic.
     *
     * @param sortBy Sort field ("relevance", "name", "version", "created_at")
     * @param sortAsc True for ascending order
     * @param hasRank True when the SELECT includes a rank column (FTS queries)
     * @return SQL ORDER BY clause (without "ORDER BY" keyword)
     */
    @SuppressWarnings("PMD.CyclomaticComplexity")
    private static String buildOrderBy(
        final String sortBy, final boolean sortAsc, final boolean hasRank
    ) {
        final String dir = sortAsc ? "ASC" : "DESC";
        if (sortBy == null || "relevance".equalsIgnoreCase(sortBy)) {
            return hasRank ? "rank DESC, name ASC" : "name ASC";
        }
        return switch (sortBy.toLowerCase()) {
            case "name" -> "name " + dir;
            case "version" ->
                "CASE WHEN version ~ '^\\d+'"
                + " THEN string_to_array("
                + "REGEXP_REPLACE(version, '[^0-9.].*$', ''), '.')::int[]"
                + " ELSE NULL END "
                + dir + " NULLS LAST, version " + dir;
            case "created_at" -> "created_date " + dir;
            default -> hasRank ? "rank DESC, name ASC" : "name ASC";
        };
    }

    /**
     * Build optional WHERE filter clauses for type and repo.
     * Type matching uses IN (?, ?, ?) for clarity and portability.
     *
     * @param repoType Base repo type (e.g. "maven"), or null
     * @param repoName Exact repo name, or null
     * @return SQL fragment starting with " AND ..." or empty string
     */
    private static String buildFilterClauses(final String repoType, final String repoName) {
        final StringBuilder sb = new StringBuilder();
        if (repoType != null && !repoType.isBlank()) {
            sb.append(" AND repo_type IN (?, ?, ?)");
        }
        if (repoName != null && !repoName.isBlank()) {
            sb.append(" AND repo_name = ?");
        }
        return sb.toString();
    }

    /**
     * Build a type-only WHERE filter clause for aggregation queries.
     *
     * @param repoType Base repo type, or null
     * @return SQL fragment starting with " AND ..." or empty string
     */
    private static String buildTypeFilterClause(final String repoType) {
        if (repoType != null && !repoType.isBlank()) {
            return " AND repo_type IN (?, ?, ?)";
        }
        return "";
    }

    /**
     * Set filter parameters onto a PreparedStatement starting at the given index.
     * Binds base type, base-proxy, and base-group as three separate Java strings.
     *
     * @param stmt PreparedStatement
     * @param idx 1-based parameter index to start from
     * @param repoType Base repo type filter, or null
     * @param repoName Exact repo name filter, or null
     * @return Next available parameter index
     * @throws SQLException on SQL error
     */
    private static int setFilterParams(
        final PreparedStatement stmt, final int idx,
        final String repoType, final String repoName
    ) throws SQLException {
        int next = idx;
        if (repoType != null && !repoType.isBlank()) {
            next = setTypeFilterParams(stmt, next, repoType);
        }
        if (repoName != null && !repoName.isBlank()) {
            stmt.setString(next++, repoName);
        }
        return next;
    }

    /**
     * Set type filter parameters (base, base-proxy, base-group).
     *
     * @param stmt PreparedStatement
     * @param idx 1-based parameter index to start from
     * @param repoType Base repo type
     * @return Next available parameter index
     * @throws SQLException on SQL error
     */
    private static int setTypeFilterParams(
        final PreparedStatement stmt, final int idx, final String repoType
    ) throws SQLException {
        int next = idx;
        stmt.setString(next++, repoType);
        stmt.setString(next++, repoType + "-proxy");
        stmt.setString(next++, repoType + "-group");
        return next;
    }

    /**
     * Query type-level aggregation counts for FTS searches (unfiltered by type/repo).
     * Groups by base repo type (stripping -proxy and -group suffixes).
     *
     * @param conn Open connection to reuse
     * @param ftsWhere FTS WHERE clause with one ? for the query param
     * @param queryParam The tsquery or plain-text query value
     * @return Ordered map of base type to count
     * @throws SQLException on DB error
     */
    private static Map<String, Long> queryTypeCounts(
        final Connection conn, final String ftsWhere, final String queryParam
    ) throws SQLException {
        final String sql = String.join(
            " ",
            "SELECT REGEXP_REPLACE(repo_type, '-(proxy|group)$', '') AS base_type,",
            "COUNT(*) AS cnt",
            "FROM artifacts WHERE", ftsWhere,
            "GROUP BY base_type ORDER BY cnt DESC"
        );
        final Map<String, Long> counts = new java.util.LinkedHashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, queryParam);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString("base_type"), rs.getLong("cnt"));
                }
            }
        }
        return counts;
    }

    /**
     * Query repo-level aggregation counts for FTS searches (scoped to the active type filter).
     * When no type filter is active, returns counts across all types.
     *
     * @param conn Open connection to reuse
     * @param ftsWhere FTS WHERE clause with one ? for the query param
     * @param queryParam The tsquery or plain-text query value
     * @param repoType Active type filter, or null
     * @return Ordered map of repo name to count
     * @throws SQLException on DB error
     */
    private static Map<String, Long> queryRepoCounts(
        final Connection conn, final String ftsWhere, final String queryParam,
        final String repoType
    ) throws SQLException {
        final String typeFilter = buildTypeFilterClause(repoType);
        final String sql = String.join(
            " ",
            "SELECT repo_name, COUNT(*) AS cnt",
            "FROM artifacts WHERE", ftsWhere, typeFilter,
            "GROUP BY repo_name ORDER BY cnt DESC"
        );
        final Map<String, Long> counts = new java.util.LinkedHashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, queryParam);
            if (repoType != null && !repoType.isBlank()) {
                setTypeFilterParams(stmt, 2, repoType);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString("repo_name"), rs.getLong("cnt"));
                }
            }
        }
        return counts;
    }

    /**
     * Query type-level aggregation counts for LIKE searches (unfiltered by type/repo).
     *
     * @param conn Open connection to reuse
     * @param likeWhere LIKE WHERE clause with one ? for the pattern
     * @param pattern LIKE pattern
     * @return Ordered map of base type to count
     * @throws SQLException on DB error
     */
    private static Map<String, Long> queryTypeCountsLike(
        final Connection conn, final String likeWhere, final String pattern
    ) throws SQLException {
        final String sql = String.join(
            " ",
            "SELECT REGEXP_REPLACE(repo_type, '-(proxy|group)$', '') AS base_type,",
            "COUNT(*) AS cnt",
            "FROM artifacts WHERE", likeWhere,
            "GROUP BY base_type ORDER BY cnt DESC"
        );
        final Map<String, Long> counts = new java.util.LinkedHashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, pattern);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString("base_type"), rs.getLong("cnt"));
                }
            }
        }
        return counts;
    }

    /**
     * Query repo-level aggregation counts for LIKE searches (scoped to active type filter).
     *
     * @param conn Open connection to reuse
     * @param likeWhere LIKE WHERE clause with one ? for the pattern
     * @param pattern LIKE pattern
     * @param repoType Active type filter, or null
     * @return Ordered map of repo name to count
     * @throws SQLException on DB error
     */
    private static Map<String, Long> queryRepoCountsLike(
        final Connection conn, final String likeWhere, final String pattern,
        final String repoType
    ) throws SQLException {
        final String typeFilter = buildTypeFilterClause(repoType);
        final String sql = String.join(
            " ",
            "SELECT repo_name, COUNT(*) AS cnt",
            "FROM artifacts WHERE", likeWhere, typeFilter,
            "GROUP BY repo_name ORDER BY cnt DESC"
        );
        final Map<String, Long> counts = new java.util.LinkedHashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, pattern);
            if (repoType != null && !repoType.isBlank()) {
                setTypeFilterParams(stmt, 2, repoType);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString("repo_name"), rs.getLong("cnt"));
                }
            }
        }
        return counts;
    }

    /**
     * Filtered prefix-FTS search with aggregation counts.
     */
    private static SearchResult searchFilteredPrefixFts(
        final DataSource source, final String query, final int maxResults, final int offset,
        final String repoType, final String repoName, final String sortBy, final boolean sortAsc
    ) throws SQLException {
        final String tsquery = DbArtifactIndex.buildPrefixTsQuery(query);
        if (tsquery.isEmpty()) {
            return new SearchResult(java.util.Collections.emptyList(), 0, offset, null);
        }
        final String filter = buildFilterClauses(repoType, repoName);
        final String orderBy = buildOrderBy(sortBy, sortAsc, true);
        final String searchSql = String.join(
            " ",
            "SELECT repo_type, repo_name, name, version, size, created_date, owner,",
            "ts_rank(search_tokens, to_tsquery('simple', ?)) AS rank,",
            "COUNT(*) OVER() AS total_count",
            "FROM artifacts WHERE search_tokens @@ to_tsquery('simple', ?)",
            filter,
            "ORDER BY", orderBy, "LIMIT ? OFFSET ?"
        );
        final String ftsWhere = "search_tokens @@ to_tsquery('simple', ?)";
        long totalHits = 0;
        final List<ArtifactDocument> docs = new ArrayList<>();
        try (Connection conn = source.getConnection()) {
            // Main search query with COUNT(*) OVER()
            try (PreparedStatement stmt = conn.prepareStatement(searchSql)) {
                stmt.setString(1, tsquery);
                stmt.setString(2, tsquery);
                final int next = setFilterParams(stmt, 3, repoType, repoName);
                stmt.setInt(next, maxResults);
                stmt.setInt(next + 1, offset);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        if (docs.isEmpty()) {
                            totalHits = rs.getLong("total_count");
                        }
                        docs.add(fromResultSet(rs));
                    }
                }
            }
            // Aggregation queries for sidebar facets
            final Map<String, Long> typeCounts = queryTypeCounts(conn, ftsWhere, tsquery);
            final Map<String, Long> repoCounts = queryRepoCounts(
                conn, ftsWhere, tsquery, repoType
            );
            return new SearchResult(docs, totalHits, offset, null, typeCounts, repoCounts);
        }
    }

    /**
     * Filtered exact-match FTS search with aggregation counts.
     */
    private static SearchResult searchFilteredFts(
        final DataSource source, final String query, final int maxResults, final int offset,
        final String repoType, final String repoName, final String sortBy, final boolean sortAsc
    ) throws SQLException {
        final String filter = buildFilterClauses(repoType, repoName);
        final String orderBy = buildOrderBy(sortBy, sortAsc, true);
        final String searchSql = String.join(
            " ",
            "SELECT repo_type, repo_name, name, version, size, created_date, owner,",
            "ts_rank(search_tokens, plainto_tsquery('simple', ?)) AS rank,",
            "COUNT(*) OVER() AS total_count",
            "FROM artifacts WHERE search_tokens @@ plainto_tsquery('simple', ?)",
            filter,
            "ORDER BY", orderBy, "LIMIT ? OFFSET ?"
        );
        final String ftsWhere = "search_tokens @@ plainto_tsquery('simple', ?)";
        long totalHits = 0;
        final List<ArtifactDocument> docs = new ArrayList<>();
        try (Connection conn = source.getConnection()) {
            // Main search query with COUNT(*) OVER()
            try (PreparedStatement stmt = conn.prepareStatement(searchSql)) {
                stmt.setString(1, query);
                stmt.setString(2, query);
                final int next = setFilterParams(stmt, 3, repoType, repoName);
                stmt.setInt(next, maxResults);
                stmt.setInt(next + 1, offset);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        if (docs.isEmpty()) {
                            totalHits = rs.getLong("total_count");
                        }
                        docs.add(fromResultSet(rs));
                    }
                }
            }
            // Aggregation queries for sidebar facets
            final Map<String, Long> typeCounts = queryTypeCounts(conn, ftsWhere, query);
            final Map<String, Long> repoCounts = queryRepoCounts(
                conn, ftsWhere, query, repoType
            );
            return new SearchResult(docs, totalHits, offset, null, typeCounts, repoCounts);
        }
    }

    /**
     * Filtered LIKE search with aggregation counts.
     * Wrapped in an explicit transaction so SET LOCAL statement_timeout applies.
     */
    private static SearchResult searchFilteredLike(
        final DataSource source, final String pattern, final int maxResults, final int offset,
        final String repoType, final String repoName, final String sortBy, final boolean sortAsc
    ) {
        final String filter = buildFilterClauses(repoType, repoName);
        final String orderBy = buildOrderBy(sortBy, sortAsc, false);
        final String searchSql = String.join(
            " ",
            "SELECT repo_type, repo_name, name, version, size, created_date, owner,",
            "COUNT(*) OVER() AS total_count",
            "FROM artifacts WHERE LOWER(name) LIKE LOWER(?)",
            filter,
            "ORDER BY", orderBy, "LIMIT ? OFFSET ?"
        );
        final String likeWhere = "LOWER(name) LIKE LOWER(?)";
        long totalHits = 0;
        final List<ArtifactDocument> docs = new ArrayList<>();
        Map<String, Long> typeCounts = Map.of();
        Map<String, Long> repoCounts = Map.of();
        try (Connection conn = source.getConnection()) {
            // SET LOCAL requires an explicit transaction block
            conn.setAutoCommit(false);
            try {
                try (java.sql.Statement guard = conn.createStatement()) {
                    guard.execute(
                        "SET LOCAL statement_timeout = '" + LIKE_TIMEOUT_MS + "ms'"
                    );
                }
                // Main search query with COUNT(*) OVER()
                try (PreparedStatement stmt = conn.prepareStatement(searchSql)) {
                    stmt.setString(1, pattern);
                    final int next = setFilterParams(stmt, 2, repoType, repoName);
                    stmt.setInt(next, maxResults);
                    stmt.setInt(next + 1, offset);
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            if (docs.isEmpty()) {
                                totalHits = rs.getLong("total_count");
                            }
                            docs.add(fromResultSet(rs));
                        }
                    }
                }
                // Aggregation queries for sidebar facets
                typeCounts = queryTypeCountsLike(conn, likeWhere, pattern);
                repoCounts = queryRepoCountsLike(conn, likeWhere, pattern, repoType);
                conn.commit();
            } catch (final SQLException inner) {
                conn.rollback();
                throw inner;
            }
        } catch (final SQLException ex) {
            EcsLogger.error("com.auto1.pantera.index")
                .message("Filtered LIKE search failed for pattern: " + pattern)
                .eventCategory("search")
                .eventAction("db_search_filtered_like")
                .eventOutcome("failure")
                .error(ex)
                .log();
            return SearchResult.EMPTY;
        }
        return new SearchResult(docs, totalHits, offset, null, typeCounts, repoCounts);
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
            // Use prefix-matching FTS: "test" → 'test:*' matches
            // "test", "test.txt", "testing", etc. Uses GIN index
            // for efficient search on large datasets (10M+ rows).
            try {
                final SearchResult ftsResult = DbArtifactIndex.searchWithPrefixFts(
                    this.source, query, maxResults, offset
                );
                if (ftsResult.totalHits() == 0) {
                    // Fallback to exact-match FTS (handles phrases)
                    final SearchResult exact = DbArtifactIndex.searchWithFts(
                        this.source, query, maxResults, offset
                    );
                    if (exact.totalHits() == 0) {
                        // Final fallback: LIKE search for special chars (@, /, -)
                        return DbArtifactIndex.searchWithLike(
                            this.source, "%" + query + "%", maxResults, offset
                        );
                    }
                    return exact;
                }
                return ftsResult;
            } catch (final SQLException ex) {
                // Graceful degradation: if tsvector column doesn't exist or
                // any FTS-related error occurs, fall back to LIKE
                EcsLogger.warn("com.auto1.pantera.index")
                    .message("FTS search failed, falling back to LIKE: "
                        + ex.getMessage())
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
        long totalHits = 0;
        final List<ArtifactDocument> docs = new ArrayList<>();
        try (Connection conn = source.getConnection();
             PreparedStatement stmt = conn.prepareStatement(FTS_SEARCH_SQL)) {
            stmt.setString(1, query);
            stmt.setString(2, query);
            stmt.setInt(3, maxResults);
            stmt.setInt(4, offset);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    if (docs.isEmpty()) {
                        totalHits = rs.getLong("total_count");
                    }
                    docs.add(fromResultSet(rs));
                }
            }
        }
        return new SearchResult(docs, totalHits, offset, null);
    }

    /**
     * Execute prefix-matching FTS: "test" becomes "test:*" tsquery,
     * matching "test", "test.txt", "testing", etc. Uses GIN index.
     *
     * @param source DataSource
     * @param query Raw user query
     * @param maxResults Max results per page
     * @param offset Pagination offset
     * @return SearchResult with ranked results
     * @throws SQLException On database error
     */
    private static SearchResult searchWithPrefixFts(
        final DataSource source, final String query,
        final int maxResults, final int offset
    ) throws SQLException {
        final String tsquery = DbArtifactIndex.buildPrefixTsQuery(query);
        if (tsquery.isEmpty()) {
            return new SearchResult(
                java.util.Collections.emptyList(), 0, offset, null
            );
        }
        long totalHits = 0;
        final List<ArtifactDocument> docs = new ArrayList<>();
        try (Connection conn = source.getConnection();
             PreparedStatement stmt = conn.prepareStatement(PREFIX_FTS_SEARCH_SQL)) {
            stmt.setString(1, tsquery);
            stmt.setString(2, tsquery);
            stmt.setInt(3, maxResults);
            stmt.setInt(4, offset);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    if (docs.isEmpty()) {
                        totalHits = rs.getLong("total_count");
                    }
                    docs.add(fromResultSet(rs));
                }
            }
        }
        return new SearchResult(docs, totalHits, offset, null);
    }

    /**
     * Build a prefix-matching tsquery from user input.
     * Splits on whitespace and dots, appends ":*" to each term,
     * joins with "&amp;" for AND semantics.
     * E.g. "test" → "test:*", "test txt" → "test:* &amp; txt:*"
     *
     * @param query Raw user query
     * @return tsquery string safe for to_tsquery('simple', ?)
     */
    private static String buildPrefixTsQuery(final String query) {
        final StringBuilder sb = new StringBuilder();
        for (final String word : query.trim().split("[\\s.@/\\-]+")) {
            final String clean = word.replaceAll("[^a-zA-Z0-9_]", "");
            if (!clean.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" & ");
                }
                sb.append(clean).append(":*");
            }
        }
        return sb.toString();
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
        long totalHits = 0;
        final List<ArtifactDocument> docs = new ArrayList<>();
        try (Connection conn = source.getConnection()) {
            // SET LOCAL requires an explicit transaction block to persist across statements
            conn.setAutoCommit(false);
            try {
                // Guard against runaway LIKE scans on large tables
                try (java.sql.Statement guard = conn.createStatement()) {
                    guard.execute(
                        "SET LOCAL statement_timeout = '" + LIKE_TIMEOUT_MS + "ms'"
                    );
                }
                // Single query with COUNT(*) OVER() — avoids double index scan
                try (PreparedStatement stmt = conn.prepareStatement(LIKE_SEARCH_SQL)) {
                    stmt.setString(1, pattern);
                    stmt.setInt(2, maxResults);
                    stmt.setInt(3, offset);
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            if (docs.isEmpty()) {
                                totalHits = rs.getLong("total_count");
                            }
                            docs.add(fromResultSet(rs));
                        }
                    }
                }
                conn.commit();
            } catch (final SQLException inner) {
                conn.rollback();
                throw inner;
            }
        } catch (final SQLException ex) {
            EcsLogger.error("com.auto1.pantera.index")
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
            final List<String> prefixes = pathPrefixes(artifactPath);
            final String sql = buildLocateSql(prefixes.size());
            final List<String> repos = new ArrayList<>();
            try (Connection conn = this.source.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                int idx = 1;
                for (final String prefix : prefixes) {
                    stmt.setString(idx++, prefix);
                }
                stmt.setString(idx, artifactPath);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        repos.add(rs.getString("repo_name"));
                    }
                }
            } catch (final SQLException ex) {
                EcsLogger.error("com.auto1.pantera.index")
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
    public CompletableFuture<List<String>> locateByName(final String artifactName) {
        return CompletableFuture.supplyAsync(() -> {
            final List<String> repos = new ArrayList<>();
            try (Connection conn = this.source.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(LOCATE_BY_NAME_SQL)) {
                stmt.setString(1, artifactName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        repos.add(rs.getString("repo_name"));
                    }
                }
            } catch (final SQLException ex) {
                EcsLogger.error("com.auto1.pantera.index")
                    .message("LocateByName failed for: " + artifactName)
                    .eventCategory("search")
                    .eventAction("db_locate_by_name")
                    .eventOutcome("failure")
                    .error(ex)
                    .log();
                return List.of();
            }
            return repos;
        }, this.executor);
    }

    /**
     * Decompose a path into all possible prefix candidates.
     * E.g. "com/google/guava/31.1/guava.jar" produces:
     * ["com", "com/google", "com/google/guava", "com/google/guava/31.1"]
     * (the full path itself is excluded since path_prefix must be a proper prefix).
     *
     * @param path Artifact path
     * @return List of prefix candidates (never empty — contains at least the full path)
     */
    static List<String> pathPrefixes(final String path) {
        final String clean = path.startsWith("/") ? path.substring(1) : path;
        final String[] segments = clean.split("/");
        final List<String> prefixes = new ArrayList<>(segments.length);
        final StringBuilder buf = new StringBuilder(clean.length());
        for (int i = 0; i < segments.length - 1; i++) {
            if (i > 0) {
                buf.append('/');
            }
            buf.append(segments[i]);
            prefixes.add(buf.toString());
        }
        if (prefixes.isEmpty()) {
            prefixes.add(clean);
        }
        return prefixes;
    }

    /**
     * Build locate SQL with the right number of IN placeholders.
     * Result: SELECT DISTINCT repo_name FROM artifacts
     *         WHERE path_prefix IN (?,?,...) OR name = ?
     *
     * @param prefixCount Number of prefix candidates
     * @return SQL string
     */
    private static String buildLocateSql(final int prefixCount) {
        final StringBuilder sql = new StringBuilder(LOCATE_PREFIX);
        for (int i = 0; i < prefixCount; i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('?');
        }
        sql.append(')');
        sql.append(LOCATE_NAME_CLAUSE);
        return sql.toString();
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
                EcsLogger.error("com.auto1.pantera.index")
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
                EcsLogger.error("com.auto1.pantera.index")
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
