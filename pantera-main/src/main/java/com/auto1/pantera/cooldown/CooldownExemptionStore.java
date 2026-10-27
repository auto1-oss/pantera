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
package com.auto1.pantera.cooldown;

import com.auto1.pantera.cooldown.config.CooldownExemptions;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import com.auto1.pantera.http.log.EcsLogger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Persists per-repository cooldown exemptions ({@code cooldown_exemptions},
 * V147) and keeps {@link CooldownSettings} in step with the table. Requests
 * only ever read the in-memory copy; this class runs at startup, on an admin
 * change, when another node announces a change, and once a minute.
 *
 * <p>Every read-then-swap of the in-memory copy runs while holding the shared
 * {@link CooldownSettings} monitor, together with its database read or write.
 * Store instances (the boot loader, each API verticle) share that object, so
 * a periodic refresh can never swap in a table snapshot that an admin change
 * on this node committed past in the meantime. Requests never take the
 * lock.</p>
 *
 * @since 2.2.11
 */
public final class CooldownExemptionStore {

    /**
     * Cache-invalidation channel announcing that a repository's exemptions
     * changed; the message key is the repository name.
     */
    public static final String CHANNEL = "cooldown-exemptions";

    /**
     * Columns of a rule row.
     */
    private static final String COLUMNS =
        "repo_name, pattern, expires_at, reason, created_by, created_at";

    /**
     * Database.
     */
    private final DataSource dataSource;

    /**
     * Settings holding the in-memory exemptions.
     */
    private final CooldownSettings settings;

    /**
     * Ctor.
     *
     * @param dataSource Database
     * @param settings Settings holding the in-memory exemptions
     */
    public CooldownExemptionStore(final DataSource dataSource, final CooldownSettings settings) {
        this.dataSource = dataSource;
        this.settings = settings;
    }

    /**
     * Load every repository's exemptions into the settings (startup).
     *
     * @return Number of repositories with exemptions
     */
    public int loadAll() {
        this.refresh();
        final int count = this.settings.exemptions().size();
        EcsLogger.info("com.auto1.pantera.cooldown")
            .message("Cooldown exemptions loaded for " + count + " repositories")
            .eventCategory("configuration")
            .eventAction("cooldown_exemptions_load")
            .eventOutcome("success")
            .field("log.source", "application")
            .log();
        return count;
    }

    /**
     * Re-read the whole table and swap it into the settings when it differs
     * from what is in memory: picks up renamed and deleted repositories and
     * changes another node announced while this one was not listening.
     *
     * @return True when the in-memory exemptions changed
     */
    public boolean refresh() {
        synchronized (this.settings) {
            return this.refreshLocked();
        }
    }

    private boolean refreshLocked() {
        final Map<String, List<CooldownExemptions.Rule>> byRepo = new HashMap<>();
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT " + COLUMNS + " FROM cooldown_exemptions ORDER BY repo_name, pattern"
            );
            ResultSet rows = stmt.executeQuery()) {
            while (rows.next()) {
                byRepo.computeIfAbsent(rows.getString("repo_name"), key -> new ArrayList<>())
                    .add(CooldownExemptionStore.rule(rows));
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to load cooldown exemptions", err);
        }
        final Map<String, List<CooldownExemptions.Rule>> current = new HashMap<>();
        this.settings.exemptions().forEach((repo, found) -> current.put(repo, found.rules()));
        final boolean changed = !current.equals(byRepo);
        if (changed) {
            final Map<String, CooldownExemptions> next = new HashMap<>();
            byRepo.forEach((repo, rules) -> next.put(repo, new CooldownExemptions(rules)));
            this.settings.replaceExemptions(next);
        }
        return changed;
    }

    /**
     * Exemptions of a repository now in effect (in memory).
     *
     * @param repoName Repository name
     * @return Exemptions
     */
    public CooldownExemptions current(final String repoName) {
        return this.settings.repoExemptions(repoName);
    }

    /**
     * Re-read one repository's exemptions into the settings.
     *
     * @param repoName Repository name
     * @return The repository's exemptions now in effect
     */
    public CooldownExemptions reload(final String repoName) {
        synchronized (this.settings) {
            final CooldownExemptions loaded = new CooldownExemptions(this.read(repoName));
            this.settings.setRepoExemptions(repoName, loaded);
            return loaded;
        }
    }

    /**
     * Replace one repository's exemptions, in the table and in memory.
     *
     * @param repoName Repository name
     * @param rules New rules; empty removes every exemption of the repository
     * @return The repository's exemptions now in effect
     */
    public CooldownExemptions replace(final String repoName, final List<CooldownExemptions.Rule> rules) {
        synchronized (this.settings) {
            return this.replaceLocked(repoName, rules);
        }
    }

    private CooldownExemptions replaceLocked(
        final String repoName, final List<CooldownExemptions.Rule> rules
    ) {
        try (Connection conn = this.dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM cooldown_exemptions WHERE repo_name = ?"
                )) {
                    del.setString(1, repoName);
                    del.executeUpdate();
                }
                try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO cooldown_exemptions (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)"
                )) {
                    for (final CooldownExemptions.Rule rule : rules) {
                        CooldownExemptionStore.bind(ins, repoName, rule);
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                conn.commit();
            } catch (final SQLException err) {
                conn.rollback();
                throw err;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (final SQLException err) {
            throw new IllegalStateException(
                "Failed to replace cooldown exemptions of " + repoName, err
            );
        }
        // What is in effect is what was committed: re-read it.
        final CooldownExemptions applied = new CooldownExemptions(this.read(repoName));
        this.settings.setRepoExemptions(repoName, applied);
        return applied;
    }

    private List<CooldownExemptions.Rule> read(final String repoName) {
        final List<CooldownExemptions.Rule> rules = new ArrayList<>();
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT " + COLUMNS + " FROM cooldown_exemptions WHERE repo_name = ? ORDER BY pattern"
            )) {
            stmt.setString(1, repoName);
            try (ResultSet rows = stmt.executeQuery()) {
                while (rows.next()) {
                    rules.add(CooldownExemptionStore.rule(rows));
                }
            }
        } catch (final SQLException err) {
            throw new IllegalStateException(
                "Failed to read cooldown exemptions of " + repoName, err
            );
        }
        return rules;
    }

    private static void bind(
        final PreparedStatement ins, final String repoName, final CooldownExemptions.Rule rule
    ) throws SQLException {
        ins.setString(1, repoName);
        ins.setString(2, rule.pattern());
        if (rule.expiresAt().isPresent()) {
            ins.setTimestamp(3, Timestamp.from(rule.expiresAt().get()));
        } else {
            ins.setNull(3, Types.TIMESTAMP_WITH_TIMEZONE);
        }
        ins.setString(4, rule.reason());
        ins.setString(5, rule.createdBy());
        ins.setTimestamp(6, Timestamp.from(rule.createdAt()));
    }

    private static CooldownExemptions.Rule rule(final ResultSet row) throws SQLException {
        final Timestamp expires = row.getTimestamp("expires_at");
        final Timestamp created = row.getTimestamp("created_at");
        return new CooldownExemptions.Rule(
            row.getString("pattern"),
            Optional.ofNullable(expires).map(Timestamp::toInstant),
            java.util.Objects.requireNonNullElse(row.getString("reason"), ""),
            row.getString("created_by"),
            created == null ? Instant.EPOCH : created.toInstant()
        );
    }
}
