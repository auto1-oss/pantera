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
package com.auto1.pantera.backfill;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repairs {@code version = 'UNKNOWN'} rows in the artifacts table by
 * inferring the version from the artifact {@code name} column.
 *
 * <p>The {@code name} for file-repo artifacts is the full storage path with
 * {@code /} replaced by {@code .}, e.g.:
 * {@code wkda.services.my-svc.1.5.0-SNAPSHOT.my-svc-1.5.0-20191106.pom}
 * Version detection splits on {@code .}, finds the first contiguous run of
 * tokens that each start with a digit, and rejoins them.</p>
 *
 * <p>Pagination uses {@code WHERE id > :lastId ORDER BY id LIMIT n} (cursor
 * style) so there is no OFFSET overhead. Version detection runs in Java.
 * Updates are sent as a JDBC batch per page. This runner is idempotent:
 * it only touches rows whose version is still {@code UNKNOWN}.</p>
 *
 * @since 1.21.0
 */
public final class VersionRepairRunner {

    /**
     * Logger.
     */
    private static final Logger LOG =
        LoggerFactory.getLogger(VersionRepairRunner.class);

    /**
     * Rows fetched per SELECT page (cursor-based).
     */
    private static final int PAGE_SIZE = 5_000;

    /**
     * JDBC data source.
     */
    private final DataSource source;

    /**
     * Table name (default: {@code artifacts}).
     */
    private final String table;

    /**
     * Optional repo_type filter (null = all types).
     */
    private final String repoType;

    /**
     * When true, compute but do not write updates.
     */
    private final boolean dryRun;

    /**
     * Ctor.
     *
     * @param source JDBC data source
     * @param table Table name
     * @param repoType Optional repo_type filter (null = all)
     * @param dryRun If true, no writes are performed
     */
    public VersionRepairRunner(
        final DataSource source,
        final String table,
        final String repoType,
        final boolean dryRun
    ) {
        this.source = source;
        this.table = table;
        this.repoType = repoType;
        this.dryRun = dryRun;
    }

    /**
     * Run the repair.
     *
     * @return Exit code (0 = success, 1 = error)
     */
    public int run() {
        LOG.info(
            "VersionRepair starting: table={}, repo-type={}, dry-run={}",
            this.table,
            this.repoType == null ? "(all)" : this.repoType,
            this.dryRun
        );
        long totalScanned = 0L;
        long totalUpdated = 0L;
        long totalSkipped = 0L;
        long totalConflicts = 0L;
        try (Connection conn = this.source.getConnection()) {
            conn.setAutoCommit(true);
            long lastId = 0L;
            while (true) {
                final List<long[]> ids = new ArrayList<>(PAGE_SIZE);
                final List<String> names = new ArrayList<>(PAGE_SIZE);
                this.fetchPage(conn, lastId, ids, names);
                if (ids.isEmpty()) {
                    break;
                }
                lastId = ids.get(ids.size() - 1)[0];
                final List<long[]> toUpdate = new ArrayList<>();
                final List<String> newVersions = new ArrayList<>();
                for (int i = 0; i < ids.size(); i++) {
                    final long id = ids.get(i)[0];
                    final String name = names.get(i);
                    final String version = detectVersion(name);
                    totalScanned++;
                    if ("UNKNOWN".equals(version)) {
                        totalSkipped++;
                    } else {
                        toUpdate.add(new long[]{id});
                        newVersions.add(version);
                    }
                }
                if (!toUpdate.isEmpty()) {
                    if (this.dryRun) {
                        LOG.info(
                            "[dry-run] page ending id={}: would update {} rows",
                            lastId, toUpdate.size()
                        );
                        totalUpdated += toUpdate.size();
                    } else {
                        final long[] result = this.applyBatch(
                            conn, toUpdate, newVersions
                        );
                        totalUpdated += result[0];
                        totalConflicts += result[1];
                    }
                }
                LOG.info(
                    "Progress: scanned={}, updated={}, kept-unknown={}, conflicts={}",
                    totalScanned, totalUpdated, totalSkipped, totalConflicts
                );
                if (ids.size() < PAGE_SIZE) {
                    break;
                }
            }
        } catch (final SQLException ex) {
            LOG.error("VersionRepair failed: {}", ex.getMessage(), ex);
            return 1;
        }
        LOG.info(
            "VersionRepair complete: scanned={}, updated={}, "
                + "kept-unknown={}, conflicts={}",
            totalScanned, totalUpdated, totalSkipped, totalConflicts
        );
        return 0;
    }

    /**
     * Fetch one page of UNKNOWN rows using cursor-based pagination.
     * Results are appended into {@code ids} and {@code names}.
     *
     * @param conn Active connection
     * @param lastId Cursor: only rows with id > lastId
     * @param ids Output list of [id] arrays
     * @param names Output list of name strings (parallel to ids)
     * @throws SQLException On query error
     */
    private void fetchPage(
        final Connection conn, final long lastId,
        final List<long[]> ids, final List<String> names
    ) throws SQLException {
        final String sql;
        if (this.repoType == null) {
            sql = String.format(
                "SELECT id, name FROM %s "
                    + "WHERE version = 'UNKNOWN' AND id > ? "
                    + "ORDER BY id LIMIT %d",
                this.table, PAGE_SIZE
            );
        } else {
            sql = String.format(
                "SELECT id, name FROM %s "
                    + "WHERE version = 'UNKNOWN' AND repo_type = ? AND id > ? "
                    + "ORDER BY id LIMIT %d",
                this.table, PAGE_SIZE
            );
        }
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (this.repoType == null) {
                stmt.setLong(1, lastId);
            } else {
                stmt.setString(1, this.repoType);
                stmt.setLong(2, lastId);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(new long[]{rs.getLong("id")});
                    names.add(rs.getString("name"));
                }
            }
        }
    }

    /**
     * Apply a batch of version updates using a single
     * {@code UPDATE ... FROM (VALUES ...)} statement. One SQL round trip per
     * page instead of one per row.
     *
     * @param conn Active connection
     * @param toUpdate List of [id] arrays
     * @param newVersions Detected versions, parallel to toUpdate
     * @return Two-element array [updated, conflict-skipped]
     * @throws SQLException On unexpected error
     */
    private long[] applyBatch(
        final Connection conn,
        final List<long[]> toUpdate,
        final List<String> newVersions
    ) throws SQLException {
        if (toUpdate.isEmpty()) {
            return new long[]{0L, 0L};
        }
        // Build: UPDATE t SET version = v.new_version
        //        FROM (VALUES (?,?),(?,?),...) AS v(id, new_version)
        //        WHERE t.id = v.id AND t.version = 'UNKNOWN'
        //          AND NOT EXISTS (SELECT 1 FROM <table> x
        //              WHERE x.repo_name = t.repo_name
        //                AND x.name = t.name
        //                AND x.version = v.new_version)
        //
        // The NOT EXISTS guard skips rows where the inferred version
        // would collide with an existing (repo_name, name, version)
        // tuple — e.g. a maven-metadata.xml.sha1 that already has
        // a row for both 'UNKNOWN' and '1.0.0-SNAPSHOT'. Without it,
        // the UPDATE violates the unique constraint and aborts the
        // whole batch.
        final StringBuilder sql = new StringBuilder();
        sql.append(String.format("UPDATE %s t SET version = v.new_version FROM (VALUES ", this.table));
        for (int i = 0; i < toUpdate.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append("(?::bigint,?::text)");
        }
        sql.append(String.format(
            ") AS v(id, new_version) WHERE t.id = v.id AND t.version = 'UNKNOWN'"
            + " AND NOT EXISTS (SELECT 1 FROM %s x"
            + " WHERE x.repo_name = t.repo_name AND x.name = t.name"
            + " AND x.version = v.new_version)",
            this.table
        ));
        conn.setAutoCommit(false);
        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            int pos = 1;
            for (int i = 0; i < toUpdate.size(); i++) {
                stmt.setLong(pos++, toUpdate.get(i)[0]);
                stmt.setString(pos++, newVersions.get(i));
            }
            final long updated = stmt.executeLargeUpdate();
            conn.commit();
            return new long[]{updated, toUpdate.size() - updated};
        } catch (final SQLException ex) {
            conn.rollback();
            throw ex;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    /**
     * Infer a version from a dotted artifact name.
     *
     * <p>Walks dot-split tokens left to right; the first contiguous run of
     * tokens that each start with a digit (or {@code v} followed by a digit)
     * is re-joined with {@code .} and returned as the version.
     * Returns {@code "UNKNOWN"} if no such run is found.</p>
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code a.b.c.1.5.0-SNAPSHOT.artifact-1.5.pom} → {@code 1.5.0-SNAPSHOT}</li>
     *   <li>{@code reports.2024.q1.pdf} → {@code 2024}</li>
     *   <li>{@code config.application.yml} → {@code UNKNOWN}</li>
     *   <li>{@code v1.0.0.app.tar.gz} → {@code v1.0.0}</li>
     * </ul>
     *
     * <p>This method mirrors {@code FileVersionDetector.detect()} in
     * pantera-core. The backfill module does not depend on pantera-core
     * to keep the CLI lightweight, so the logic is duplicated here.</p>
     *
     * @param name Dotted artifact name from the DB
     * @return Detected version or {@code "UNKNOWN"}
     */
    static String detectVersion(final String name) {
        if (name == null || name.isEmpty()) {
            return "UNKNOWN";
        }
        final String[] tokens = name.split("\\.");
        int start = -1;
        int end = -1;
        for (int i = 0; i < tokens.length; i++) {
            final String tok = tokens[i];
            if (!tok.isEmpty() && isVersionToken(tok)) {
                if (start == -1) {
                    start = i;
                }
                end = i;
            } else if (start != -1) {
                break;
            }
        }
        if (start == -1) {
            return "UNKNOWN";
        }
        // Check if the preceding token ends with -{digits} — those digits
        // are the real version start, split away by the dot tokenizer.
        // e.g. "elinks-current-0" + "11" → version should be "0.11"
        //      "nginx-1" + "24" + "0"   → version should be "1.24.0"
        final StringBuilder sb = new StringBuilder();
        if (start > 0) {
            final String prev = tokens[start - 1];
            final int lastHyphen = prev.lastIndexOf('-');
            if (lastHyphen >= 0 && lastHyphen < prev.length() - 1) {
                final String tail = prev.substring(lastHyphen + 1);
                if (isVersionToken(tail)) {
                    sb.append(tail).append('.');
                }
            }
        }
        for (int i = start; i <= end; i++) {
            if (i > start) {
                sb.append('.');
            }
            sb.append(tokens[i]);
        }
        return sb.toString();
    }

    /**
     * Check whether a dot-split token looks like part of a version.
     * Tokens starting with a digit or with {@code v} followed by a digit
     * qualify.
     *
     * @param token Token to test
     * @return True if version-like
     */
    private static boolean isVersionToken(final String token) {
        if (token.isEmpty()) {
            return false;
        }
        final char first = token.charAt(0);
        if (Character.isDigit(first)) {
            return true;
        }
        return first == 'v' && token.length() > 1
            && Character.isDigit(token.charAt(1));
    }
}
