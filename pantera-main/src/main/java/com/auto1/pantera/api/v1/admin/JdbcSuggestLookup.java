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
package com.auto1.pantera.api.v1.admin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * {@link SuggestLookup} over the {@code artifacts} index and the
 * {@code artifact_cooldowns} / {@code artifact_cooldowns_history} tables.
 *
 * <p>Every word of the query is one parameterised {@code ILIKE '%word%'}
 * (wildcards escaped) — the match the cooldown list search and the global
 * package search run, served by the trigram indexes on
 * {@code artifacts.name} and {@code artifact_cooldowns.artifact}. Each
 * source returns at most {@code limit} distinct (repository, name) rows,
 * exact and prefix matches and short names first.</p>
 *
 * @since 2.2.9
 */
public final class JdbcSuggestLookup implements SuggestLookup {

    /**
     * Statement timeout, seconds: type-ahead must not hold a connection.
     */
    private static final int TIMEOUT_S = 10;

    /**
     * Candidate ordering: exact, prefix, then short names, then by name.
     */
    private static final String ORDER =
        " ORDER BY CASE WHEN LOWER(name) = ? THEN 0 WHEN LOWER(name) LIKE ? ESCAPE '\\'"
            + " THEN 1 ELSE 2 END, LENGTH(name), name LIMIT ?";

    /**
     * Database.
     */
    private final DataSource source;

    /**
     * Ctor.
     *
     * @param source Database
     */
    public JdbcSuggestLookup(final DataSource source) {
        this.source = source;
    }

    @Override
    public List<SuggestRow> rows(
        final Collection<String> families, final SuggestQuery query, final int limit
    ) {
        if (query.empty() || limit <= 0) {
            return List.of();
        }
        final List<SuggestRow> out = new ArrayList<>();
        try (Connection conn = this.source.getConnection()) {
            final Filter index = new Filter(conn, "name", families, query);
            out.addAll(
                JdbcSuggestLookup.select(
                    conn,
                    "SELECT repo_type, repo_name, name, MAX(path_prefix) AS path_prefix"
                        + " FROM artifacts WHERE " + index.sql()
                        + " GROUP BY repo_type, repo_name, name" + ORDER,
                    JdbcSuggestLookup.params(index.params(), query, limit),
                    SuggestRow.INDEX
                )
            );
            final Filter cool = new Filter(conn, "artifact", families, query);
            final List<Object> both = new ArrayList<>(cool.params());
            both.addAll(cool.params());
            out.addAll(
                JdbcSuggestLookup.select(
                    conn,
                    "SELECT repo_type, repo_name, name, NULL AS path_prefix FROM ("
                        + "SELECT repo_type, repo_name, artifact AS name"
                        + " FROM artifact_cooldowns WHERE " + cool.sql()
                        + " UNION SELECT repo_type, repo_name, artifact AS name"
                        + " FROM artifact_cooldowns_history WHERE " + cool.sql()
                        + ") matched" + ORDER,
                    JdbcSuggestLookup.params(both, query, limit),
                    SuggestRow.COOLDOWN
                )
            );
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to query package suggestions", err);
        }
        return out;
    }

    @Override
    public Map<String, String> mavenPaths(final Collection<String> names) {
        final Map<String, String> out = new HashMap<>();
        if (names.isEmpty()) {
            return out;
        }
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT name, MAX(path_prefix) AS path_prefix FROM artifacts"
                    + " WHERE name = ANY(?) AND path_prefix IS NOT NULL GROUP BY name"
            )) {
            stmt.setQueryTimeout(TIMEOUT_S);
            stmt.setArray(1, conn.createArrayOf("varchar", names.toArray()));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("name"), rs.getString("path_prefix"));
                }
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to query maven index paths", err);
        }
        return out;
    }

    /**
     * Filter parameters followed by the ordering parameters and the limit.
     *
     * @param filter Filter parameters
     * @param query Query
     * @param limit Limit
     * @return Parameters
     */
    private static List<Object> params(
        final List<Object> filter, final SuggestQuery query, final int limit
    ) {
        final List<Object> out = new ArrayList<>(filter);
        out.add(query.text());
        out.add(query.prefixPattern());
        out.add(limit);
        return out;
    }

    /**
     * Run a candidate query.
     *
     * @param conn Connection
     * @param sql SQL
     * @param params Parameters in order
     * @param source Row source
     * @return Rows
     * @throws SQLException On failure
     */
    private static List<SuggestRow> select(
        final Connection conn, final String sql, final List<Object> params, final String source
    ) throws SQLException {
        final List<SuggestRow> out = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setQueryTimeout(TIMEOUT_S);
            for (int idx = 0; idx < params.size(); idx += 1) {
                stmt.setObject(idx + 1, params.get(idx));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(new SuggestRow(
                        source, rs.getString("repo_type"), rs.getString("repo_name"),
                        rs.getString("name"), rs.getString("path_prefix")
                    ));
                }
            }
        }
        return out;
    }

    /**
     * WHERE clause over one name column: every word through one of its
     * patterns, and the families when given.
     *
     * @since 2.2.9
     */
    private static final class Filter {

        /**
         * SQL.
         */
        private final String sql;

        /**
         * Parameters.
         */
        private final List<Object> params;

        /**
         * Ctor.
         *
         * @param conn Connection, for SQL arrays
         * @param column Name column
         * @param families Families, empty for all
         * @param query Query
         * @throws SQLException On array creation failure
         */
        Filter(
            final Connection conn, final String column,
            final Collection<String> families, final SuggestQuery query
        ) throws SQLException {
            final List<String> clauses = new ArrayList<>();
            final List<Object> binds = new ArrayList<>();
            for (final List<String> alts : query.patterns()) {
                clauses.add(
                    alts.stream().map(alt -> column + " ILIKE ? ESCAPE '\\'")
                        .collect(Collectors.joining(" OR ", "(", ")"))
                );
                binds.addAll(alts);
            }
            if (!families.isEmpty()) {
                clauses.add("(LOWER(repo_type) = ANY(?) OR LOWER(repo_type) LIKE ANY(?))");
                binds.add(conn.createArrayOf("varchar", families.toArray()));
                binds.add(conn.createArrayOf(
                    "varchar", families.stream().map(fam -> fam + "-%").toArray()
                ));
            }
            this.sql = String.join(" AND ", clauses);
            this.params = binds;
        }

        /**
         * SQL.
         *
         * @return WHERE clause body
         */
        String sql() {
            return this.sql;
        }

        /**
         * Parameters.
         *
         * @return Parameters in order
         */
        List<Object> params() {
            return this.params;
        }
    }
}
