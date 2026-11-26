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
package com.auto1.pantera.auth.signin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;

/**
 * The lines in the {@code sign_in_failures} table: shared by all nodes
 * and kept across restarts.
 *
 * <p>Events are added with one statement per line in one transaction,
 * sorted by their line so that two nodes that write the same lines take
 * the row locks in the same order.</p>
 *
 * @since 2.2.15
 */
public final class DbSignInFailureStore implements SignInFailureStore {

    /**
     * Adds an event to its line.
     */
    private static final String SAVE = String.join(
        " ",
        "INSERT INTO sign_in_failures (kind, username, client_ip, token_id, reason, verified,",
        "user_agent, sign_in_path, hits, refused, first_seen, last_seen, failures,",
        "blocked_until, actor)",
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        "ON CONFLICT ON CONSTRAINT sign_in_failures_line DO UPDATE SET",
        "hits = sign_in_failures.hits + EXCLUDED.hits,",
        "refused = sign_in_failures.refused + EXCLUDED.refused,",
        "first_seen = LEAST(sign_in_failures.first_seen, EXCLUDED.first_seen),",
        "reason = CASE WHEN EXCLUDED.last_seen >= sign_in_failures.last_seen",
        "THEN EXCLUDED.reason ELSE sign_in_failures.reason END,",
        "verified = CASE WHEN EXCLUDED.last_seen >= sign_in_failures.last_seen",
        "THEN EXCLUDED.verified ELSE sign_in_failures.verified END,",
        "user_agent = CASE WHEN EXCLUDED.last_seen >= sign_in_failures.last_seen",
        "THEN EXCLUDED.user_agent ELSE sign_in_failures.user_agent END,",
        "sign_in_path = CASE WHEN EXCLUDED.last_seen >= sign_in_failures.last_seen",
        "THEN EXCLUDED.sign_in_path ELSE sign_in_failures.sign_in_path END,",
        "failures = CASE WHEN EXCLUDED.last_seen >= sign_in_failures.last_seen",
        "THEN EXCLUDED.failures ELSE sign_in_failures.failures END,",
        "blocked_until = CASE WHEN EXCLUDED.last_seen >= sign_in_failures.last_seen",
        "THEN EXCLUDED.blocked_until ELSE sign_in_failures.blocked_until END,",
        "actor = CASE WHEN EXCLUDED.last_seen >= sign_in_failures.last_seen",
        "THEN EXCLUDED.actor ELSE sign_in_failures.actor END,",
        "last_seen = GREATEST(sign_in_failures.last_seen, EXCLUDED.last_seen)"
    );

    /**
     * What a query matches. The user name is compared whole or as a part,
     * the address as a part; an empty text matches all.
     */
    private static final String MATCH = String.join(
        " ",
        "FROM sign_in_failures WHERE kind = ?",
        "AND (? = '' OR (? AND username = ?) OR (NOT ? AND username LIKE ? ESCAPE '!'))",
        "AND (? = '' OR client_ip LIKE ? ESCAPE '!')"
    );

    /**
     * Columns of a line.
     */
    private static final String COLUMNS = String.join(
        " ",
        "SELECT kind, username, client_ip, token_id, reason, verified, user_agent,",
        "sign_in_path, hits, refused, first_seen, last_seen, failures, blocked_until, actor"
    );

    /**
     * Database.
     */
    private final DataSource source;

    /**
     * Ctor.
     *
     * @param source Database
     */
    public DbSignInFailureStore(final DataSource source) {
        this.source = source;
    }

    @Override
    public void save(final Collection<SignInFailure> events) {
        if (events.isEmpty()) {
            return;
        }
        final List<SignInFailure> sorted = new ArrayList<>(events);
        sorted.sort(java.util.Comparator.comparing(SignInFailure::line));
        try (Connection conn = this.source.getConnection()) {
            final boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement(DbSignInFailureStore.SAVE)) {
                for (final SignInFailure event : sorted) {
                    DbSignInFailureStore.bind(stmt, event);
                    stmt.addBatch();
                }
                stmt.executeBatch();
                conn.commit();
            } catch (final SQLException err) {
                conn.rollback();
                throw err;
            } finally {
                conn.setAutoCommit(auto);
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to save the sign-in failures", err);
        }
    }

    @Override
    public Page list(final Query query) {
        final List<SignInFailure> items = new ArrayList<>(16);
        int total = 0;
        try (Connection conn = this.source.getConnection()) {
            try (PreparedStatement count = conn.prepareStatement(
                "SELECT COUNT(*) " + DbSignInFailureStore.MATCH
            )) {
                DbSignInFailureStore.match(count, query);
                try (ResultSet res = count.executeQuery()) {
                    if (res.next()) {
                        total = res.getInt(1);
                    }
                }
            }
            try (PreparedStatement page = conn.prepareStatement(
                String.join(
                    " ", DbSignInFailureStore.COLUMNS, DbSignInFailureStore.MATCH,
                    "ORDER BY last_seen DESC, username, client_ip, token_id LIMIT ? OFFSET ?"
                )
            )) {
                final int next = DbSignInFailureStore.match(page, query);
                page.setInt(next, Math.max(query.limit(), 0));
                page.setInt(next + 1, Math.max(query.offset(), 0));
                try (ResultSet res = page.executeQuery()) {
                    while (res.next()) {
                        items.add(DbSignInFailureStore.line(res));
                    }
                }
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to list the sign-in failures", err);
        }
        return new Page(items, total);
    }

    @Override
    public int purge(final Instant before) {
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "DELETE FROM sign_in_failures WHERE last_seen < ?"
            )) {
            stmt.setTimestamp(1, Timestamp.from(before));
            return stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to purge the sign-in failures", err);
        }
    }

    private static void bind(final PreparedStatement stmt, final SignInFailure event)
        throws SQLException {
        stmt.setString(1, event.kind());
        stmt.setString(2, event.username());
        stmt.setString(3, event.clientIp());
        stmt.setString(4, event.tokenId());
        stmt.setString(5, event.reason());
        stmt.setBoolean(6, event.verified());
        stmt.setString(7, event.userAgent());
        stmt.setString(8, event.path());
        stmt.setLong(9, event.hits());
        stmt.setLong(10, event.refused());
        stmt.setTimestamp(11, Timestamp.from(event.firstSeen()));
        stmt.setTimestamp(12, Timestamp.from(event.lastSeen()));
        stmt.setInt(13, event.failures());
        if (event.blockedUntil() == null) {
            stmt.setNull(14, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            stmt.setTimestamp(14, Timestamp.from(event.blockedUntil()));
        }
        stmt.setString(15, event.actor());
    }

    /**
     * Bind the parameters of {@link #MATCH}.
     *
     * @param stmt Statement
     * @param query Query
     * @return Index of the next parameter
     * @throws SQLException On error
     */
    private static int match(final PreparedStatement stmt, final Query query)
        throws SQLException {
        final String user = query.user().strip().toLowerCase(Locale.ROOT);
        final String address = query.clientIp().strip();
        stmt.setString(1, query.kind());
        stmt.setString(2, user);
        stmt.setBoolean(3, query.exact());
        stmt.setString(4, user);
        stmt.setBoolean(5, query.exact());
        stmt.setString(6, '%' + DbSignInFailureStore.literal(user) + '%');
        stmt.setString(7, address);
        stmt.setString(8, '%' + DbSignInFailureStore.literal(address) + '%');
        return 9;
    }

    /**
     * A text as a literal of a LIKE pattern with the escape character
     * {@code !}.
     *
     * @param text Text to look for
     * @return Pattern that matches the text itself
     */
    private static String literal(final String text) {
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static SignInFailure line(final ResultSet res) throws SQLException {
        final Timestamp until = res.getTimestamp(14);
        final Instant blocked;
        if (until == null) {
            blocked = null;
        } else {
            blocked = until.toInstant();
        }
        return new SignInFailure(
            res.getString(1), res.getString(2), res.getString(3), res.getString(4),
            res.getString(5), res.getBoolean(6), res.getString(7), res.getString(8),
            res.getLong(9), res.getLong(10), res.getTimestamp(11).toInstant(),
            res.getTimestamp(12).toInstant(), res.getInt(13), blocked, res.getString(15)
        );
    }
}
