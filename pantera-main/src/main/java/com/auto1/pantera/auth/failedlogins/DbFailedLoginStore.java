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
package com.auto1.pantera.auth.failedlogins;

import com.auto1.pantera.settings.policy.FailedLoginsConfig;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Failed logins kept in the {@code login_failures} table, so every node
 * counts the same failures and a restart forgets none.
 *
 * <p>A failure is counted in one transaction that locks the name's row:
 * concurrent failures, on one node or on several, are each counted. All
 * times are the database's ({@code clock_timestamp()}), never a node's.</p>
 *
 * @since 2.2.15
 */
public final class DbFailedLoginStore implements FailedLoginStore {

    /**
     * Reads the state of a name with the database time, whether the name
     * has a row or not.
     */
    private static final String READ = String.join(
        " ",
        "SELECT clock_timestamp() AS now, f.failures, f.last_failure_at, f.locked_at",
        "FROM (SELECT 1) one LEFT JOIN login_failures f ON f.username = ?"
    );

    /**
     * Makes sure a name has a row.
     */
    private static final String ENSURE =
        "INSERT INTO login_failures (username) VALUES (?) ON CONFLICT (username) DO NOTHING";

    /**
     * Locks the row of a name and reads it with the database time.
     */
    private static final String LOCK = String.join(
        " ",
        "SELECT clock_timestamp() AS now, failures, last_failure_at, locked_at",
        "FROM login_failures WHERE username = ? FOR UPDATE"
    );

    /**
     * Writes the state of a name.
     */
    private static final String WRITE = String.join(
        " ",
        "UPDATE login_failures SET failures = ?, last_failure_at = ?, locked_at = ?,",
        "client_ip = ?, user_agent = ?, sign_in_path = ?, credential = ?",
        "WHERE username = ?"
    );

    /**
     * Removes the row of a name and hands back what it held.
     */
    private static final String FORGET = String.join(
        " ",
        "DELETE FROM login_failures WHERE username = ?",
        "RETURNING failures, last_failure_at, locked_at"
    );

    /**
     * Lists the locked names.
     */
    private static final String LOCKED = String.join(
        " ",
        "SELECT username, failures, last_failure_at, locked_at,",
        "client_ip, user_agent, sign_in_path, credential",
        "FROM login_failures WHERE locked_at IS NOT NULL ORDER BY locked_at, username"
    );

    /**
     * Reads the row of one name.
     */
    private static final String ENTRY = String.join(
        " ",
        "SELECT username, failures, last_failure_at, locked_at,",
        "client_ip, user_agent, sign_in_path, credential",
        "FROM login_failures WHERE username = ?"
    );

    /**
     * Names with failed logins on record, most recent failure first.
     */
    private static final String COUNTED = String.join(
        " ",
        "SELECT username FROM login_failures WHERE failures > 0",
        "ORDER BY last_failure_at DESC NULLS LAST, username LIMIT ?"
    );

    /**
     * Removes the rows of all locked names.
     */
    private static final String UNLOCK_ALL =
        "DELETE FROM login_failures WHERE locked_at IS NOT NULL RETURNING username";

    /**
     * Database.
     */
    private final DataSource source;

    /**
     * Ctor.
     *
     * @param source Database
     */
    public DbFailedLoginStore(final DataSource source) {
        this.source = source;
    }

    @Override
    public Seen read(final String name) {
        // An uncounted name still gets the database's time: the key no row has.
        final String key = new Counted(name).name().orElse("");
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(DbFailedLoginStore.READ)) {
            stmt.setString(1, key);
            try (ResultSet res = stmt.executeQuery()) {
                if (!res.next()) {
                    throw new IllegalStateException("The database did not tell its time");
                }
                return DbFailedLoginStore.seen(res);
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to read the failed logins of a user", err);
        }
    }

    @Override
    public Seen fail(final String name, final FailedLoginsConfig policy, final LoginOrigin origin) {
        final Optional<String> counted = new Counted(name).name();
        final Seen res;
        if (counted.isPresent()) {
            res = this.count(counted.get(), policy, origin);
        } else {
            res = new Seen(LoginState.NONE, this.read("").now());
        }
        return res;
    }

    @Override
    public LoginState forget(final String name) {
        final Optional<String> counted = new Counted(name).name();
        LoginState state = LoginState.NONE;
        if (counted.isPresent()) {
            try (Connection conn = this.source.getConnection();
                PreparedStatement stmt = conn.prepareStatement(DbFailedLoginStore.FORGET)) {
                stmt.setString(1, counted.get());
                try (ResultSet res = stmt.executeQuery()) {
                    if (res.next()) {
                        state = DbFailedLoginStore.state(res);
                    }
                }
            } catch (final SQLException err) {
                throw new IllegalStateException(
                    "Failed to forget the failed logins of a user", err
                );
            }
        }
        return state;
    }

    @Override
    public Optional<LockedName> entry(final String name) {
        final Optional<String> counted = new Counted(name).name();
        Optional<LockedName> found = Optional.empty();
        if (counted.isPresent()) {
            try (Connection conn = this.source.getConnection();
                PreparedStatement stmt = conn.prepareStatement(DbFailedLoginStore.ENTRY)) {
                stmt.setString(1, counted.get());
                try (ResultSet res = stmt.executeQuery()) {
                    if (res.next()) {
                        found = Optional.of(DbFailedLoginStore.named(res));
                    }
                }
            } catch (final SQLException err) {
                throw new IllegalStateException(
                    "Failed to read the failed logins of a user", err
                );
            }
        }
        return found;
    }

    @Override
    public List<LockedName> locked() {
        final List<LockedName> names = new ArrayList<>(16);
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(DbFailedLoginStore.LOCKED);
            ResultSet res = stmt.executeQuery()) {
            while (res.next()) {
                names.add(DbFailedLoginStore.named(res));
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to list the locked users", err);
        }
        return names;
    }

    @Override
    public List<String> counted() {
        final List<String> names = new ArrayList<>(16);
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(DbFailedLoginStore.COUNTED)) {
            stmt.setInt(1, FailedLoginStore.MAX_COUNTED);
            try (ResultSet res = stmt.executeQuery()) {
                while (res.next()) {
                    names.add(res.getString(1));
                }
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to list the names with failed logins", err);
        }
        return names;
    }

    @Override
    public List<String> unlockAll() {
        final List<String> names = new ArrayList<>(16);
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(DbFailedLoginStore.UNLOCK_ALL);
            ResultSet res = stmt.executeQuery()) {
            while (res.next()) {
                names.add(res.getString(1));
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to unlock the locked users", err);
        }
        return names;
    }

    /**
     * Count one failure of a name under its row lock.
     *
     * @param name Counted name
     * @param policy Policy
     * @param origin Where the login came from
     * @return The state after the failure
     */
    private Seen count(final String name, final FailedLoginsConfig policy, final LoginOrigin origin) {
        try (Connection conn = this.source.getConnection()) {
            final boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement stmt = conn.prepareStatement(DbFailedLoginStore.ENSURE)) {
                    stmt.setString(1, name);
                    stmt.executeUpdate();
                }
                final Seen before;
                try (PreparedStatement stmt = conn.prepareStatement(DbFailedLoginStore.LOCK)) {
                    stmt.setString(1, name);
                    try (ResultSet res = stmt.executeQuery()) {
                        if (!res.next()) {
                            throw new IllegalStateException(
                                "The row of a failed login vanished while it was counted"
                            );
                        }
                        before = DbFailedLoginStore.seen(res);
                    }
                }
                final LoginState after = before.state().failed(before.now(), policy);
                try (PreparedStatement stmt = conn.prepareStatement(DbFailedLoginStore.WRITE)) {
                    stmt.setInt(1, after.failures());
                    stmt.setTimestamp(2, DbFailedLoginStore.timestamp(after.lastFailure()));
                    stmt.setTimestamp(3, DbFailedLoginStore.timestamp(after.lockedAt()));
                    stmt.setString(4, origin.clientIp());
                    stmt.setString(5, origin.userAgent());
                    stmt.setString(6, origin.path());
                    stmt.setString(7, origin.credential());
                    stmt.setString(8, name);
                    stmt.executeUpdate();
                }
                conn.commit();
                return new Seen(after, before.now());
            } catch (final SQLException | RuntimeException err) {
                conn.rollback();
                throw err;
            } finally {
                conn.setAutoCommit(auto);
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to count a failed login", err);
        }
    }

    private static LockedName named(final ResultSet res) throws SQLException {
        final LoginState state = DbFailedLoginStore.state(res);
        return new LockedName(
            res.getString("username"), state.lockedAt(), state.failures(), state.lastFailure(),
            new LoginOrigin(
                res.getString("client_ip"), res.getString("user_agent"),
                res.getString("sign_in_path"), res.getString("credential")
            )
        );
    }

    private static Seen seen(final ResultSet res) throws SQLException {
        return new Seen(DbFailedLoginStore.state(res), res.getTimestamp("now").toInstant());
    }

    private static LoginState state(final ResultSet res) throws SQLException {
        final int failures = res.getInt("failures");
        final LoginState state;
        if (res.wasNull()) {
            state = LoginState.NONE;
        } else {
            state = new LoginState(
                failures,
                DbFailedLoginStore.instant(res.getTimestamp("last_failure_at")),
                DbFailedLoginStore.instant(res.getTimestamp("locked_at"))
            );
        }
        return state;
    }

    private static Instant instant(final Timestamp stamp) {
        final Instant res;
        if (stamp == null) {
            res = null;
        } else {
            res = stamp.toInstant();
        }
        return res;
    }

    private static Timestamp timestamp(final Instant instant) {
        final Timestamp res;
        if (instant == null) {
            res = null;
        } else {
            res = Timestamp.from(instant);
        }
        return res;
    }
}
