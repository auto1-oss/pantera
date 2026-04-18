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
import com.auto1.pantera.http.context.ContextualExecutorService;

import javax.sql.DataSource;
import java.sql.Array;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.auto1.pantera.index.SearchQueryParser.FieldFilter;
import com.auto1.pantera.index.SearchQueryParser.MatchType;

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
     * Sort field enum — Fix 1: replaces raw String in buildOrderBy.
     */
    public enum SortField {
        /** Sort by artifact name. */
        NAME,
        /** Sort by version (natural ordering). */
        VERSION,
        /** Sort by creation date. */
        DATE,
        /** Sort by search relevance rank (default). */
        RELEVANCE
    }

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
     * Statement timeout for locateByName queries (ms).
     * Configurable via PANTERA_INDEX_LOCATE_TIMEOUT_MS env var.
     * The query uses idx_artifacts_locate btree and should complete in &lt;1ms;
     * 500ms ceiling only triggers on pathological conditions (DB pressure,
     * missing index, lock contention). Timeout surfaces as SQLException which
     * already maps to Optional.empty() → full fanout safety net.
     */
    private static final long LOCATE_BY_NAME_TIMEOUT_MS =
        ConfigDefaults.getLong("PANTERA_INDEX_LOCATE_TIMEOUT_MS", 500L);

    /**
     * Statement timeout for FTS aggregation queries (ms).
     * Fix 6: prevents slow facet aggregations from blocking the response.
     */
    private static final long FTS_AGG_TIMEOUT_MS = 3000L;

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
     * Total count SQL — reads from materialized view for O(1) performance.
     * Bonus fix: avoids COUNT(*) full table scan.
     */
    private static final String MV_TOTAL_COUNT_SQL =
        "SELECT artifact_count FROM mv_artifact_totals";

    /**
     * Fallback total count SQL used when the materialized view is empty or unavailable.
     */
    private static final String TOTAL_COUNT_SQL = "SELECT COUNT(*) FROM artifacts";

    /**
     * Bounded queue capacity for the default executor.
     * When the queue is full, {@link ThreadPoolExecutor.CallerRunsPolicy} executes
     * the task on the submitting thread, propagating backpressure to callers instead
     * of buffering unboundedly and OOM-ing the JVM under DB latency spikes.
     * Configurable via PANTERA_INDEX_EXECUTOR_QUEUE env var.
     */
    private static final int QUEUE_SIZE =
        ConfigDefaults.getInt("PANTERA_INDEX_EXECUTOR_QUEUE", 500);

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
     * Creates a bounded thread pool sized to available processors.
     * Uses a {@code QUEUE_SIZE}-slot {@link LinkedBlockingQueue} and
     * {@link ThreadPoolExecutor.CallerRunsPolicy} to apply backpressure when the
     * queue fills rather than buffering tasks unboundedly.
     *
     * @param source JDBC DataSource
     */
    public DbArtifactIndex(final DataSource source) {
        this(source, createDbIndexExecutor(), true);
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
     * Build the default bounded executor for DB index operations.
     * Queue size is configurable via PANTERA_INDEX_EXECUTOR_QUEUE (default 500).
     * When the queue is full, {@link ThreadPoolExecutor.CallerRunsPolicy} runs the
     * task on the submitting thread, propagating backpressure instead of OOM-ing
     * the JVM before the per-query statement timeout fires.
     *
     * <p>The returned {@link ExecutorService} is a
     * {@link ContextualExecutorService} wrapping the raw pool: every task-submission
     * entry point ({@code execute}, {@code submit(Callable/Runnable)},
     * {@code invokeAll}, {@code invokeAny}) snapshots the submitting thread's
     * Log4j2 {@link ThreadContext} (ECS fields) and the active Elastic APM span at
     * submit time, then restores them on the runner thread for the task's duration
     * — so ECS fields and the trace context stay attached across the thread hop.
     *
     * @return Contextualising wrapper around a bounded thread pool
     */
    private static ExecutorService createDbIndexExecutor() {
        final int poolSize = Math.max(2, Runtime.getRuntime().availableProcessors());
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            poolSize, poolSize,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(QUEUE_SIZE),
            r -> {
                final Thread thread = new Thread(
                    r,
                    "db-artifact-index-" + THREAD_COUNTER.incrementAndGet()
                );
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        pool.allowCoreThreadTimeOut(false);
        EcsLogger.info("com.auto1.pantera.index")
            .message("DbArtifactIndex executor initialised ("
                + poolSize + " threads, queue=" + QUEUE_SIZE + ", policy=caller-runs)")
            .eventCategory("configuration")
            .eventAction("pool_init")
            .log();
        // WI-post-03a: ContextualExecutorService contextualises EVERY submit path
        // (execute, submit(Callable/Runnable), invokeAll, invokeAny) — fixes the
        // latent bypass where submit(Callable) went straight to the underlying
        // pool with empty ThreadContext / no APM span.
        return ContextualExecutorService.wrap(pool);
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
                    .eventCategory("database")
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
                    .eventCategory("database")
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
        return this.search(query, maxResults, offset, repoType, repoName, sortBy, sortAsc, null);
    }

    /**
     * Full-text search with optional server-side filtering and sorting.
     * Fix 5: accepts allowedRepos list for permission-aware SQL filtering.
     *
     * @param query Search query string
     * @param maxResults Maximum results to return
     * @param offset Starting offset for pagination
     * @param repoType Optional repo type base filter
     * @param repoName Optional exact repository name filter
     * @param sortBy Sort field string (mapped to SortField enum internally)
     * @param sortAsc True for ascending, false for descending
     * @param allowedRepos Allowed repository names; null means no restriction
     * @return Search result with matching documents
     */
    public CompletableFuture<SearchResult> search(
        final String query, final int maxResults, final int offset,
        final String repoType, final String repoName, final String sortBy, final boolean sortAsc,
        final List<String> allowedRepos
    ) {
        // Fix 2: facets only on first page
        final boolean includeFacets = offset == 0;
        return CompletableFuture.supplyAsync(() -> {
            final boolean uselike = query.contains("%") || query.contains("_");
            if (uselike) {
                return DbArtifactIndex.searchFilteredLike(
                    this.source, query, maxResults, offset, repoType, repoName,
                    sortBy, sortAsc, includeFacets, allowedRepos
                );
            }
            try {
                final SearchResult ftsResult = DbArtifactIndex.searchFilteredPrefixFts(
                    this.source, query, maxResults, offset, repoType, repoName,
                    sortBy, sortAsc, includeFacets, allowedRepos
                );
                if (ftsResult.totalHits() == 0) {
                    final SearchResult exact = DbArtifactIndex.searchFilteredFts(
                        this.source, query, maxResults, offset, repoType, repoName,
                        sortBy, sortAsc, includeFacets, allowedRepos
                    );
                    if (exact.totalHits() == 0) {
                        return DbArtifactIndex.searchFilteredLike(
                            this.source, "%" + query + "%", maxResults, offset,
                            repoType, repoName, sortBy, sortAsc, includeFacets, allowedRepos
                        );
                    }
                    return exact;
                }
                return ftsResult;
            } catch (final SQLException ex) {
                EcsLogger.warn("com.auto1.pantera.index")
                    .message("FTS search failed, falling back to LIKE: " + ex.getMessage())
                    .eventCategory("database")
                    .eventAction("db_fts_fallback")
                    .error(ex)
                    .log();
                return DbArtifactIndex.searchFilteredLike(
                    this.source, "%" + query + "%", maxResults, offset,
                    repoType, repoName, sortBy, sortAsc, includeFacets, allowedRepos
                );
            }
        }, this.executor);
    }

    /**
     * Full-text search with structured field filters from {@link SearchQueryParser}.
     * Field filters (name, version) are appended as additional SQL predicates.
     * The repo and type parameters take precedence; callers should already have
     * extracted repo/type values from the parsed query and passed them here.
     *
     * @param query FTS query string (bare terms only, from SearchQuery.ftsQuery())
     * @param maxResults Maximum results to return
     * @param offset Starting offset for pagination
     * @param repoType Optional repo type base filter (from URL param or query repo:)
     * @param repoName Optional exact repository name filter
     * @param sortBy Sort field string
     * @param sortAsc True for ascending, false for descending
     * @param allowedRepos Allowed repository names; null means no restriction
     * @param fieldFilters Additional field filters (name:, version:) from parsed query
     * @return Search result with matching documents
     */
    @SuppressWarnings("PMD.ParameterNumber")
    public CompletableFuture<SearchResult> search(
        final String query, final int maxResults, final int offset,
        final String repoType, final String repoName, final String sortBy, final boolean sortAsc,
        final List<String> allowedRepos, final List<FieldFilter> fieldFilters
    ) {
        final boolean includeFacets = offset == 0;
        return CompletableFuture.supplyAsync(() -> {
            final String fts = query == null ? "" : query;
            final boolean uselike = fts.contains("%") || fts.contains("_");
            if (uselike) {
                return DbArtifactIndex.searchFilteredLike(
                    this.source, fts, maxResults, offset, repoType, repoName,
                    sortBy, sortAsc, includeFacets, allowedRepos, fieldFilters
                );
            }
            if (fts.isBlank() && (fieldFilters == null || !fieldFilters.isEmpty())) {
                // No FTS term — run a filter-only LIKE search with a match-all pattern
                return DbArtifactIndex.searchFilteredLike(
                    this.source, "%", maxResults, offset, repoType, repoName,
                    sortBy, sortAsc, includeFacets, allowedRepos, fieldFilters
                );
            }
            try {
                final SearchResult ftsResult = DbArtifactIndex.searchFilteredPrefixFts(
                    this.source, fts, maxResults, offset, repoType, repoName,
                    sortBy, sortAsc, includeFacets, allowedRepos, fieldFilters
                );
                if (ftsResult.totalHits() == 0) {
                    final SearchResult exact = DbArtifactIndex.searchFilteredFts(
                        this.source, fts, maxResults, offset, repoType, repoName,
                        sortBy, sortAsc, includeFacets, allowedRepos, fieldFilters
                    );
                    if (exact.totalHits() == 0) {
                        return DbArtifactIndex.searchFilteredLike(
                            this.source, "%" + fts + "%", maxResults, offset,
                            repoType, repoName, sortBy, sortAsc, includeFacets,
                            allowedRepos, fieldFilters
                        );
                    }
                    return exact;
                }
                return ftsResult;
            } catch (final SQLException ex) {
                EcsLogger.warn("com.auto1.pantera.index")
                    .message("FTS search failed, falling back to LIKE: " + ex.getMessage())
                    .eventCategory("database")
                    .eventAction("db_fts_fallback")
                    .error(ex)
                    .log();
                return DbArtifactIndex.searchFilteredLike(
                    this.source, "%" + fts + "%", maxResults, offset,
                    repoType, repoName, sortBy, sortAsc, includeFacets,
                    allowedRepos, fieldFilters
                );
            }
        }, this.executor);
    }

    /**
     * Build the ORDER BY clause based on sort parameters.
     * Fix 1: accepts {@link SortField} enum instead of raw String.
     * For version sort, uses integer array comparison for natural ordering
     * (4.1, 4.2, ..., 4.9, 4.10) instead of lexicographic.
     *
     * @param field Sort field enum value
     * @param sortAsc True for ascending order
     * @param hasRank True when the SELECT includes a rank column (FTS queries)
     * @return SQL ORDER BY clause (without "ORDER BY" keyword)
     */
    @SuppressWarnings("PMD.CyclomaticComplexity")
    private static String buildOrderBy(
        final SortField field, final boolean sortAsc, final boolean hasRank
    ) {
        final String dir = sortAsc ? "ASC" : "DESC";
        if (field == null || field == SortField.RELEVANCE) {
            return hasRank ? "rank DESC, name ASC" : "name ASC";
        }
        return switch (field) {
            case NAME -> "name " + dir;
            case VERSION ->
                "version_sort " + dir + " NULLS LAST, version " + dir;
            case DATE -> "created_date " + dir;
            default -> hasRank ? "rank DESC, name ASC" : "name ASC";
        };
    }

    /**
     * Map a raw sort string to a {@link SortField} enum value.
     * Unknown/null values map to RELEVANCE.
     *
     * @param raw Raw sort string from query parameter
     * @return Corresponding SortField, never null
     */
    public static SortField toSortField(final String raw) {
        if (raw == null) {
            return SortField.RELEVANCE;
        }
        return switch (raw.toLowerCase()) {
            case "name" -> SortField.NAME;
            case "version" -> SortField.VERSION;
            case "created_at" -> SortField.DATE;
            default -> SortField.RELEVANCE;
        };
    }

    /**
     * Build optional WHERE filter clauses for type, repo, and allowed repos.
     * Fix 5: adds AND repo_name = ANY(?) when allowedRepos is non-null.
     *
     * @param repoType Base repo type (e.g. "maven"), or null
     * @param repoName Exact repo name, or null
     * @param allowedRepos Allowed repo names (permission filter), or null
     * @return SQL fragment starting with " AND ..." or empty string
     */
    private static String buildFilterClauses(
        final String repoType, final String repoName, final List<String> allowedRepos
    ) {
        final StringBuilder sb = new StringBuilder();
        if (repoType != null && !repoType.isBlank()) {
            sb.append(" AND repo_type IN (?, ?, ?)");
        }
        if (repoName != null && !repoName.isBlank()) {
            sb.append(" AND repo_name = ?");
        }
        if (allowedRepos != null) {
            sb.append(" AND repo_name = ANY(?)");
        }
        return sb.toString();
    }

    /**
     * Build optional WHERE filter clauses without the allowedRepos parameter.
     * Legacy overload used by non-permission-aware paths.
     *
     * @param repoType Base repo type (e.g. "maven"), or null
     * @param repoName Exact repo name, or null
     * @return SQL fragment starting with " AND ..." or empty string
     */
    private static String buildFilterClauses(final String repoType, final String repoName) {
        return buildFilterClauses(repoType, repoName, null);
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
     * Build additional WHERE clauses for structured field filters (name, version, etc.).
     * Each filter with one value produces one predicate; multiple values become OR clauses.
     * The column mapping is: name→name, version→version, repo→repo_name, type→repo_type.
     *
     * @param fieldFilters List of field filters from SearchQueryParser (may be empty)
     * @return SQL fragment starting with " AND ..." or empty string
     */
    private static String buildFieldFilterClauses(final List<FieldFilter> fieldFilters) {
        if (fieldFilters == null || fieldFilters.isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (final FieldFilter filter : fieldFilters) {
            final String col = fieldToColumn(filter.field());
            if (col == null) {
                continue;
            }
            if (filter.values().size() == 1) {
                sb.append(singleValuePredicate(col, filter.matchType()));
            } else {
                // Multiple values → OR within a field: AND (col PRED ? OR col PRED ?)
                final String bare = bareValuePredicate(col, filter.matchType());
                sb.append(" AND (");
                for (int i = 0; i < filter.values().size(); i++) {
                    if (i > 0) {
                        sb.append(" OR ");
                    }
                    sb.append(bare);
                }
                sb.append(')');
            }
        }
        return sb.toString();
    }

    /**
     * Build a single predicate fragment with leading " AND " for a column.
     * Used for single-value filters in a WHERE clause.
     *
     * @param col SQL column name
     * @param matchType Match type
     * @return SQL predicate fragment with leading " AND "
     */
    private static String singleValuePredicate(final String col, final MatchType matchType) {
        return " AND " + bareValuePredicate(col, matchType);
    }

    /**
     * Build a bare predicate (no leading AND) for a column.
     * Used inside multi-value OR groups.
     *
     * @param col SQL column name
     * @param matchType Match type
     * @return SQL predicate without leading AND
     */
    private static String bareValuePredicate(final String col, final MatchType matchType) {
        return switch (matchType) {
            case EXACT -> col + " = ?";
            case PREFIX -> col + " LIKE ?";
            default -> "LOWER(" + col + ") LIKE ?";
        };
    }

    /**
     * Map a SearchQueryParser field name to the SQL column name.
     *
     * @param field Parser field name
     * @return SQL column name, or null if unknown
     */
    private static String fieldToColumn(final String field) {
        return switch (field) {
            case "name" -> "name";
            case "version" -> "version";
            case "repo" -> "repo_name";
            case "type" -> "repo_type";
            default -> null;
        };
    }

    /**
     * Bind field filter parameter values to a PreparedStatement.
     * Values are bound in the same order as {@link #buildFieldFilterClauses(List)}.
     * ILIKE wraps the value in %; PREFIX appends %; EXACT binds as-is.
     *
     * @param stmt PreparedStatement
     * @param idx 1-based parameter index to start from
     * @param fieldFilters Field filters from SearchQueryParser
     * @return Next available parameter index
     * @throws SQLException on SQL error
     */
    private static int setFieldFilterParams(
        final PreparedStatement stmt, final int idx, final List<FieldFilter> fieldFilters
    ) throws SQLException {
        if (fieldFilters == null || fieldFilters.isEmpty()) {
            return idx;
        }
        int next = idx;
        for (final FieldFilter filter : fieldFilters) {
            if (fieldToColumn(filter.field()) == null) {
                continue;
            }
            for (final String value : filter.values()) {
                final String bound = switch (filter.matchType()) {
                    case ILIKE -> "%" + value.toLowerCase() + "%";
                    case PREFIX -> value + "%";
                    default -> value;
                };
                stmt.setString(next++, bound);
            }
        }
        return next;
    }

    /**
     * Set filter parameters onto a PreparedStatement starting at the given index.
     * Fix 5: binds allowedRepos as a SQL array when non-null.
     *
     * @param stmt PreparedStatement
     * @param idx 1-based parameter index to start from
     * @param repoType Base repo type filter, or null
     * @param repoName Exact repo name filter, or null
     * @param allowedRepos Allowed repo names, or null
     * @param conn Connection needed to create SQL arrays
     * @return Next available parameter index
     * @throws SQLException on SQL error
     */
    private static int setFilterParams(
        final PreparedStatement stmt, final int idx,
        final String repoType, final String repoName,
        final List<String> allowedRepos, final Connection conn
    ) throws SQLException {
        int next = idx;
        if (repoType != null && !repoType.isBlank()) {
            next = setTypeFilterParams(stmt, next, repoType);
        }
        if (repoName != null && !repoName.isBlank()) {
            stmt.setString(next++, repoName);
        }
        if (allowedRepos != null) {
            final Array arr = conn.createArrayOf(
                "text", allowedRepos.toArray(new String[0])
            );
            stmt.setArray(next++, arr);
        }
        return next;
    }

    /**
     * Set filter parameters without allowedRepos (legacy overload for non-filtered paths).
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
     * Query type-level aggregation counts for FTS searches.
     * Fix 7: uses GROUP BY repo_type (index-friendly) and merges suffixes in Java.
     * Fix 6: applies SET LOCAL statement_timeout before running.
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
        // Fix 6: set timeout before aggregation
        try (java.sql.Statement guard = conn.createStatement()) {
            guard.execute("SET LOCAL statement_timeout = '" + FTS_AGG_TIMEOUT_MS + "ms'");
        }
        // Fix 7: GROUP BY repo_type (index-friendly), merge suffixes in Java
        final String sql = String.join(
            " ",
            "SELECT repo_type, COUNT(*) AS cnt",
            "FROM artifacts WHERE", ftsWhere,
            "GROUP BY repo_type ORDER BY cnt DESC"
        );
        final Map<String, Long> rawCounts = new java.util.LinkedHashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, queryParam);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rawCounts.put(rs.getString("repo_type"), rs.getLong("cnt"));
                }
            }
        }
        // Fix 7: merge -proxy and -group suffixes into base type in Java
        return mergeTypeSuffixes(rawCounts);
    }

    /**
     * Merge repo_type suffix variants (-proxy, -group) into the base type.
     * Fix 7: done in Java after an index-friendly GROUP BY repo_type query.
     *
     * @param rawCounts Map of repo_type to count (may include suffixed variants)
     * @return Map of base type to merged count, ordered by count descending
     */
    private static Map<String, Long> mergeTypeSuffixes(final Map<String, Long> rawCounts) {
        final Map<String, Long> merged = new java.util.LinkedHashMap<>();
        for (final Map.Entry<String, Long> entry : rawCounts.entrySet()) {
            final String type = entry.getKey();
            final String base;
            if (type != null && type.endsWith("-proxy")) {
                base = type.substring(0, type.length() - "-proxy".length());
            } else if (type != null && type.endsWith("-group")) {
                base = type.substring(0, type.length() - "-group".length());
            } else {
                base = type;
            }
            merged.merge(base, entry.getValue(), Long::sum);
        }
        // Re-sort by count descending
        final List<Map.Entry<String, Long>> entries = new ArrayList<>(merged.entrySet());
        entries.sort(java.util.Map.Entry.<String, Long>comparingByValue().reversed());
        final Map<String, Long> sorted = new java.util.LinkedHashMap<>();
        for (final Map.Entry<String, Long> e : entries) {
            sorted.put(e.getKey(), e.getValue());
        }
        return sorted;
    }

    /**
     * Query repo-level aggregation counts for FTS searches (scoped to the active type filter).
     * When no type filter is active, returns counts across all types.
     * Fix 6: applies SET LOCAL statement_timeout before running (timeout already set in
     * queryTypeCounts call upstream, but we set again in case called independently).
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
     * Fix 7: uses GROUP BY repo_type and merges suffixes in Java.
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
            "SELECT repo_type, COUNT(*) AS cnt",
            "FROM artifacts WHERE", likeWhere,
            "GROUP BY repo_type ORDER BY cnt DESC"
        );
        final Map<String, Long> rawCounts = new java.util.LinkedHashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, pattern);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rawCounts.put(rs.getString("repo_type"), rs.getLong("cnt"));
                }
            }
        }
        return mergeTypeSuffixes(rawCounts);
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
     * Query type-level aggregation with multiple bind parameters.
     * Used when field filters add extra WHERE clauses beyond the base FTS/LIKE.
     *
     * @param conn Open connection
     * @param whereClause Full WHERE clause (may contain multiple ?)
     * @param params Parameters to bind in order
     * @return Ordered map of base type to count
     * @throws SQLException on DB error
     */
    private static Map<String, Long> queryTypeCountsMultiParam(
        final Connection conn, final String whereClause, final List<Object> params
    ) throws SQLException {
        try (java.sql.Statement guard = conn.createStatement()) {
            guard.execute("SET LOCAL statement_timeout = '" + FTS_AGG_TIMEOUT_MS + "ms'");
        }
        final String sql = "SELECT repo_type, COUNT(*) AS cnt FROM artifacts WHERE "
            + whereClause + " GROUP BY repo_type ORDER BY cnt DESC";
        final Map<String, Long> rawCounts = new java.util.LinkedHashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                if (params.get(i) instanceof String) {
                    stmt.setString(i + 1, (String) params.get(i));
                } else if (params.get(i) instanceof java.sql.Array) {
                    stmt.setArray(i + 1, (java.sql.Array) params.get(i));
                }
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rawCounts.put(rs.getString("repo_type"), rs.getLong("cnt"));
                }
            }
        }
        return mergeTypeSuffixes(rawCounts);
    }

    /**
     * Query repo-level aggregation with multiple bind parameters.
     * Used when field filters add extra WHERE clauses beyond the base FTS/LIKE.
     *
     * @param conn Open connection
     * @param whereClause Full WHERE clause (may contain multiple ?)
     * @param params Parameters to bind in order
     * @param repoType Active type filter, or null
     * @return Ordered map of repo name to count
     * @throws SQLException on DB error
     */
    private static Map<String, Long> queryRepoCountsMultiParam(
        final Connection conn, final String whereClause, final List<Object> params,
        final String repoType
    ) throws SQLException {
        final String typeFilter = buildTypeFilterClause(repoType);
        final String sql = "SELECT repo_name, COUNT(*) AS cnt FROM artifacts WHERE "
            + whereClause + typeFilter + " GROUP BY repo_name ORDER BY cnt DESC";
        final Map<String, Long> counts = new java.util.LinkedHashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            int pos = 1;
            for (int i = 0; i < params.size(); i++) {
                if (params.get(i) instanceof String) {
                    stmt.setString(pos++, (String) params.get(i));
                } else if (params.get(i) instanceof java.sql.Array) {
                    stmt.setArray(pos++, (java.sql.Array) params.get(i));
                }
            }
            if (repoType != null && !repoType.isBlank()) {
                setTypeFilterParams(stmt, pos, repoType);
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
     * Run a fallback COUNT(*) query with the same WHERE clause to get totalHits.
     * Fix 3: used when main result set is empty AND offset > 0.
     *
     * @param conn Open connection
     * @param whereClause WHERE clause fragment (without "WHERE" keyword)
     * @param bindParams Parameters to bind to the WHERE clause
     * @return Total row count matching the WHERE clause
     * @throws SQLException on DB error
     */
    private static long fallbackCount(
        final Connection conn, final String whereClause, final Object... bindParams
    ) throws SQLException {
        final String sql = "SELECT COUNT(*) FROM artifacts WHERE " + whereClause;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < bindParams.length; i++) {
                if (bindParams[i] instanceof String) {
                    stmt.setString(i + 1, (String) bindParams[i]);
                } else if (bindParams[i] instanceof Array) {
                    stmt.setArray(i + 1, (Array) bindParams[i]);
                }
            }
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    /**
     * Filtered prefix-FTS search with aggregation counts.
     * Fix 2: includeFacets controls whether facet queries run.
     * Fix 3: fallback COUNT when empty result + non-zero offset.
     * Fix 5: allowedRepos adds AND repo_name = ANY(?) filter.
     * Fix 6: SET LOCAL statement_timeout before FTS aggregations.
     */
    @SuppressWarnings("PMD.CyclomaticComplexity")
    private static SearchResult searchFilteredPrefixFts(
        final DataSource source, final String query, final int maxResults, final int offset,
        final String repoType, final String repoName, final String sortBy, final boolean sortAsc,
        final boolean includeFacets, final List<String> allowedRepos
    ) throws SQLException {
        return searchFilteredPrefixFts(
            source, query, maxResults, offset, repoType, repoName,
            sortBy, sortAsc, includeFacets, allowedRepos, null
        );
    }

    /**
     * Filtered prefix-FTS search with field filters.
     *
     * @param fieldFilters Additional structured field filters; may be null
     */
    @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.ParameterNumber"})
    private static SearchResult searchFilteredPrefixFts(
        final DataSource source, final String query, final int maxResults, final int offset,
        final String repoType, final String repoName, final String sortBy, final boolean sortAsc,
        final boolean includeFacets, final List<String> allowedRepos,
        final List<FieldFilter> fieldFilters
    ) throws SQLException {
        final String tsquery = DbArtifactIndex.buildPrefixTsQuery(query);
        if (tsquery.isEmpty()) {
            return new SearchResult(java.util.Collections.emptyList(), 0, offset, null);
        }
        final SortField field = toSortField(sortBy);
        final String filter = buildFilterClauses(repoType, repoName, allowedRepos)
            + buildFieldFilterClauses(fieldFilters);
        final String orderBy = buildOrderBy(field, sortAsc, true);
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
            // Fix 6: need a transaction for SET LOCAL to work in aggregations
            conn.setAutoCommit(false);
            try {
                // Main search query with COUNT(*) OVER()
                try (PreparedStatement stmt = conn.prepareStatement(searchSql)) {
                    stmt.setString(1, tsquery);
                    stmt.setString(2, tsquery);
                    int next = setFilterParams(stmt, 3, repoType, repoName, allowedRepos, conn);
                    next = setFieldFilterParams(stmt, next, fieldFilters);
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
                // Fix 3: if empty page at non-zero offset, run fallback COUNT
                if (docs.isEmpty() && offset > 0 && totalHits == 0) {
                    final String countWhere = "search_tokens @@ to_tsquery('simple', ?)" + filter;
                    final List<Object> countParams = buildCountParams(
                        tsquery, repoType, repoName, allowedRepos, conn
                    );
                    appendFieldFilterCountParams(countParams, fieldFilters);
                    totalHits = fallbackCount(conn, countWhere, countParams.toArray());
                }
                // Fix 2: skip facets on pages other than first
                Map<String, Long> typeCounts = Map.of();
                Map<String, Long> repoCounts = Map.of();
                if (includeFacets) {
                    // Fix 6: queryTypeCounts sets timeout internally
                    try {
                        final String facetWhere = ftsWhere
                            + buildFilterClauses(null, null, allowedRepos)
                            + buildFieldFilterClauses(fieldFilters);
                        final List<Object> facetParams = new ArrayList<>();
                        facetParams.add(tsquery);
                        if (allowedRepos != null) {
                            facetParams.add(conn.createArrayOf("varchar",
                                allowedRepos.toArray(new String[0])));
                        }
                        appendFieldFilterCountParams(facetParams, fieldFilters);
                        typeCounts = queryTypeCountsMultiParam(
                            conn, facetWhere, facetParams
                        );
                        repoCounts = queryRepoCountsMultiParam(
                            conn, facetWhere, facetParams, repoType
                        );
                    } catch (final SQLException ex) {
                        // Fix 6: on timeout, return empty facet maps
                        EcsLogger.warn("com.auto1.pantera.index")
                            .message("FTS aggregation timed out, returning empty facets")
                            .eventCategory("database")
                            .eventAction("db_fts_agg_timeout")
                            .error(ex)
                            .log();
                        typeCounts = Map.of();
                        repoCounts = Map.of();
                    }
                }
                conn.commit();
                return new SearchResult(docs, totalHits, offset, null, typeCounts, repoCounts);
            } catch (final SQLException ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    /**
     * Filtered exact-match FTS search with aggregation counts.
     * Fix 2: includeFacets controls whether facet queries run.
     * Fix 3: fallback COUNT when empty result + non-zero offset.
     * Fix 5: allowedRepos adds AND repo_name = ANY(?) filter.
     * Fix 6: SET LOCAL statement_timeout before FTS aggregations.
     */
    @SuppressWarnings("PMD.CyclomaticComplexity")
    private static SearchResult searchFilteredFts(
        final DataSource source, final String query, final int maxResults, final int offset,
        final String repoType, final String repoName, final String sortBy, final boolean sortAsc,
        final boolean includeFacets, final List<String> allowedRepos
    ) throws SQLException {
        return searchFilteredFts(
            source, query, maxResults, offset, repoType, repoName,
            sortBy, sortAsc, includeFacets, allowedRepos, null
        );
    }

    /**
     * Filtered exact-match FTS search with field filters.
     *
     * @param fieldFilters Additional structured field filters; may be null
     */
    @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.ParameterNumber"})
    private static SearchResult searchFilteredFts(
        final DataSource source, final String query, final int maxResults, final int offset,
        final String repoType, final String repoName, final String sortBy, final boolean sortAsc,
        final boolean includeFacets, final List<String> allowedRepos,
        final List<FieldFilter> fieldFilters
    ) throws SQLException {
        final SortField field = toSortField(sortBy);
        final String filter = buildFilterClauses(repoType, repoName, allowedRepos)
            + buildFieldFilterClauses(fieldFilters);
        final String orderBy = buildOrderBy(field, sortAsc, true);
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
            conn.setAutoCommit(false);
            try {
                // Main search query with COUNT(*) OVER()
                try (PreparedStatement stmt = conn.prepareStatement(searchSql)) {
                    stmt.setString(1, query);
                    stmt.setString(2, query);
                    int next = setFilterParams(stmt, 3, repoType, repoName, allowedRepos, conn);
                    next = setFieldFilterParams(stmt, next, fieldFilters);
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
                // Fix 3: if empty page at non-zero offset, run fallback COUNT
                if (docs.isEmpty() && offset > 0 && totalHits == 0) {
                    final String countWhere = "search_tokens @@ plainto_tsquery('simple', ?)" + filter;
                    final List<Object> countParams = buildCountParams(
                        query, repoType, repoName, allowedRepos, conn
                    );
                    appendFieldFilterCountParams(countParams, fieldFilters);
                    totalHits = fallbackCount(conn, countWhere, countParams.toArray());
                }
                // Fix 2: skip facets on pages other than first
                Map<String, Long> typeCounts = Map.of();
                Map<String, Long> repoCounts = Map.of();
                if (includeFacets) {
                    try {
                        final String facetWhere = ftsWhere
                            + buildFilterClauses(null, null, allowedRepos)
                            + buildFieldFilterClauses(fieldFilters);
                        final List<Object> facetParams = new ArrayList<>();
                        facetParams.add(query);
                        if (allowedRepos != null) {
                            facetParams.add(conn.createArrayOf("varchar",
                                allowedRepos.toArray(new String[0])));
                        }
                        appendFieldFilterCountParams(facetParams, fieldFilters);
                        typeCounts = queryTypeCountsMultiParam(
                            conn, facetWhere, facetParams
                        );
                        repoCounts = queryRepoCountsMultiParam(
                            conn, facetWhere, facetParams, repoType
                        );
                    } catch (final SQLException ex) {
                        // Fix 6: on timeout, return empty facet maps
                        EcsLogger.warn("com.auto1.pantera.index")
                            .message("FTS aggregation timed out, returning empty facets")
                            .eventCategory("database")
                            .eventAction("db_fts_agg_timeout")
                            .error(ex)
                            .log();
                        typeCounts = Map.of();
                        repoCounts = Map.of();
                    }
                }
                conn.commit();
                return new SearchResult(docs, totalHits, offset, null, typeCounts, repoCounts);
            } catch (final SQLException ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    /**
     * Filtered LIKE search with aggregation counts.
     * Wrapped in an explicit transaction so SET LOCAL statement_timeout applies.
     * Fix 2: includeFacets controls whether facet queries run.
     * Fix 3: fallback COUNT when empty result + non-zero offset.
     * Fix 5: allowedRepos adds AND repo_name = ANY(?) filter.
     */
    @SuppressWarnings("PMD.CyclomaticComplexity")
    private static SearchResult searchFilteredLike(
        final DataSource source, final String pattern, final int maxResults, final int offset,
        final String repoType, final String repoName, final String sortBy, final boolean sortAsc,
        final boolean includeFacets, final List<String> allowedRepos
    ) {
        return searchFilteredLike(
            source, pattern, maxResults, offset, repoType, repoName,
            sortBy, sortAsc, includeFacets, allowedRepos, null
        );
    }

    /**
     * Filtered LIKE search with field filters.
     *
     * @param fieldFilters Additional structured field filters; may be null
     */
    @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.ParameterNumber"})
    private static SearchResult searchFilteredLike(
        final DataSource source, final String pattern, final int maxResults, final int offset,
        final String repoType, final String repoName, final String sortBy, final boolean sortAsc,
        final boolean includeFacets, final List<String> allowedRepos,
        final List<FieldFilter> fieldFilters
    ) {
        final SortField field = toSortField(sortBy);
        final String filter = buildFilterClauses(repoType, repoName, allowedRepos)
            + buildFieldFilterClauses(fieldFilters);
        final String orderBy = buildOrderBy(field, sortAsc, false);
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
                    int next = setFilterParams(stmt, 2, repoType, repoName, allowedRepos, conn);
                    next = setFieldFilterParams(stmt, next, fieldFilters);
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
                // Fix 3: if empty page at non-zero offset, run fallback COUNT
                if (docs.isEmpty() && offset > 0 && totalHits == 0) {
                    final String countWhere = "LOWER(name) LIKE LOWER(?)" + filter;
                    final List<Object> countParams = buildCountParams(
                        pattern, repoType, repoName, allowedRepos, conn
                    );
                    appendFieldFilterCountParams(countParams, fieldFilters);
                    totalHits = fallbackCount(conn, countWhere, countParams.toArray());
                }
                // Fix 2: skip facets on pages other than first
                if (includeFacets) {
                    final String facetWhere = likeWhere
                        + buildFilterClauses(null, null, allowedRepos)
                        + buildFieldFilterClauses(fieldFilters);
                    final List<Object> facetParams = new ArrayList<>();
                    facetParams.add(pattern);
                    if (allowedRepos != null) {
                        facetParams.add(conn.createArrayOf("varchar",
                            allowedRepos.toArray(new String[0])));
                    }
                    appendFieldFilterCountParams(facetParams, fieldFilters);
                    typeCounts = queryTypeCountsMultiParam(
                        conn, facetWhere, facetParams
                    );
                    repoCounts = queryRepoCountsMultiParam(
                        conn, facetWhere, facetParams, repoType
                    );
                }
                conn.commit();
            } catch (final SQLException inner) {
                conn.rollback();
                throw inner;
            }
        } catch (final SQLException ex) {
            EcsLogger.error("com.auto1.pantera.index")
                .message("Filtered LIKE search failed for pattern: " + pattern)
                .eventCategory("database")
                .eventAction("db_search_filtered_like")
                .eventOutcome("failure")
                .error(ex)
                .log();
            return SearchResult.EMPTY;
        }
        return new SearchResult(docs, totalHits, offset, null, typeCounts, repoCounts);
    }

    /**
     * Build the list of count parameters for a fallback COUNT query.
     * Mirrors the WHERE parameter binding order of the main search query.
     * Fix 3 helper.
     *
     * @param queryParam The FTS/LIKE query param (already the first bound value)
     * @param repoType Type filter or null
     * @param repoName Name filter or null
     * @param allowedRepos Allowed repos array or null
     * @param conn Connection for creating SQL arrays
     * @return Ordered list of bind values
     * @throws SQLException on SQL error
     */
    private static List<Object> buildCountParams(
        final String queryParam, final String repoType, final String repoName,
        final List<String> allowedRepos, final Connection conn
    ) throws SQLException {
        final List<Object> params = new ArrayList<>();
        params.add(queryParam);
        if (repoType != null && !repoType.isBlank()) {
            params.add(repoType);
            params.add(repoType + "-proxy");
            params.add(repoType + "-group");
        }
        if (repoName != null && !repoName.isBlank()) {
            params.add(repoName);
        }
        if (allowedRepos != null) {
            params.add(conn.createArrayOf("text", allowedRepos.toArray(new String[0])));
        }
        return params;
    }

    /**
     * Append field filter count parameters to an existing param list.
     * Mirrors the value binding order of {@link #setFieldFilterParams(PreparedStatement, int, List)}.
     *
     * @param params Existing parameter list (modified in place)
     * @param fieldFilters Field filters; may be null or empty
     */
    private static void appendFieldFilterCountParams(
        final List<Object> params, final List<FieldFilter> fieldFilters
    ) {
        if (fieldFilters == null || fieldFilters.isEmpty()) {
            return;
        }
        for (final FieldFilter filter : fieldFilters) {
            if (fieldToColumn(filter.field()) == null) {
                continue;
            }
            for (final String value : filter.values()) {
                final String bound = switch (filter.matchType()) {
                    case ILIKE -> "%" + value.toLowerCase() + "%";
                    case PREFIX -> value + "%";
                    default -> value;
                };
                params.add(bound);
            }
        }
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
                    .eventCategory("database")
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
                .eventCategory("database")
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
                    .eventCategory("database")
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
    public CompletableFuture<Optional<List<String>>> locateByName(final String artifactName) {
        return CompletableFuture.supplyAsync(() -> {
            final List<String> repos = new ArrayList<>();
            try (Connection conn = this.source.getConnection()) {
                // SET LOCAL requires an explicit transaction block to persist across statements
                conn.setAutoCommit(false);
                try {
                    try (java.sql.Statement guard = conn.createStatement()) {
                        guard.execute(
                            "SET LOCAL statement_timeout = '" + LOCATE_BY_NAME_TIMEOUT_MS + "ms'"
                        );
                    }
                    try (PreparedStatement stmt = conn.prepareStatement(LOCATE_BY_NAME_SQL)) {
                        stmt.setString(1, artifactName);
                        try (ResultSet rs = stmt.executeQuery()) {
                            while (rs.next()) {
                                repos.add(rs.getString("repo_name"));
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
                    .message("LocateByName failed for: " + artifactName)
                    .eventCategory("database")
                    .eventAction("locate_by_name")
                    .eventOutcome("failure")
                    .error(ex)
                    .log();
                return Optional.empty();
            }
            return Optional.of(repos);
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
            long count = -1L;
            // Bonus: try materialized view first (O(1)), fall back to COUNT(*) if empty
            try (Connection conn = this.source.getConnection()) {
                try (PreparedStatement stmt = conn.prepareStatement(MV_TOTAL_COUNT_SQL);
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        final long mvCount = rs.getLong(1);
                        if (mvCount > 0) {
                            count = mvCount;
                        }
                    }
                } catch (final SQLException ex) {
                    // View may not exist — fall through to COUNT(*)
                    EcsLogger.warn("com.auto1.pantera.index")
                        .message("mv_artifact_totals unavailable, falling back to COUNT(*)")
                        .eventCategory("database")
                        .eventAction("db_stats_mv_fallback")
                        .error(ex)
                        .log();
                }
                if (count < 0) {
                    try (PreparedStatement stmt = conn.prepareStatement(TOTAL_COUNT_SQL);
                         ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            count = rs.getLong(1);
                        }
                    }
                }
            } catch (final SQLException ex) {
                EcsLogger.error("com.auto1.pantera.index")
                    .message("Failed to get index stats")
                    .eventCategory("database")
                    .eventAction("db_stats")
                    .eventOutcome("failure")
                    .error(ex)
                    .log();
            }
            stats.put("documents", count);
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
                    .eventCategory("database")
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
