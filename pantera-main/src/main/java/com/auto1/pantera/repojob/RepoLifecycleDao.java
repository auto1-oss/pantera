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
package com.auto1.pantera.repojob;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.sql.DataSource;

/**
 * Rename, delete and restore of a repository, each as one database
 * transaction that touches no artifact data.
 *
 * <p>A repository's data lives under its immutable key
 * ({@code repositories.repo_key}), so a rename changes the name and the few
 * small records that refer to a repository by name (group member lists, role
 * grants, repository-scoped storage aliases, live cooldown blocks). A delete
 * moves the repository to {@code repository_tombstones} and queues the purge
 * of its data as a {@code repo_jobs} row; until the purge starts the delete
 * can be undone.</p>
 *
 * <p>The row lock on the repository (or tombstone) serialises concurrent
 * operations on the same repository across every node.</p>
 *
 * @since 2.2.14
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.GodClass"})
public final class RepoLifecycleDao {

    /**
     * How long a former name is remembered when the rename did not ask to
     * keep it working: long enough to translate artifact events that were
     * queued under the old name on any node.
     */
    static final Duration EVENT_ALIAS = Duration.ofHours(1);

    /**
     * Time since the delete, by the database clock.
     */
    private static final String AGE =
        "(EXTRACT(EPOCH FROM (NOW() - deleted_at)) * 1000)::bigint AS age_ms";

    /**
     * Config member holding a repository's settings.
     */
    private static final String REPO = "repo";

    /**
     * Group member list inside the repository settings.
     */
    private static final String MEMBERS = "members";

    /**
     * Database.
     */
    private final DataSource source;

    /**
     * Ctor.
     * @param source Database
     */
    public RepoLifecycleDao(final DataSource source) {
        this.source = source;
    }

    /**
     * Rename a repository. No artifact data and no index row is touched.
     *
     * @param name Current name
     * @param target New name
     * @param aliasDays Days the old name keeps serving requests, 0 for none
     * @param actor Who renames it, nullable
     * @return What was renamed
     * @throws RepoOpRejected When the source is missing or the target is taken
     */
    public Renamed rename(
        final String name, final String target, final int aliasDays, final String actor
    ) {
        return this.inTransaction(conn -> {
            final Row row = RepoLifecycleDao.lockRepository(conn, name)
                .orElseThrow(
                    () -> new RepoOpRejected(
                        404, "NOT_FOUND", String.format("Repository '%s' not found", name)
                    )
                );
            RepoLifecycleDao.requireNameFree(conn, target, row.key());
            RepoLifecycleDao.dropNameLeftovers(conn, target);
            RepoLifecycleDao.update(
                conn, "DELETE FROM repository_aliases WHERE alias = ?", target
            );
            RepoLifecycleDao.update(
                conn,
                "UPDATE repositories SET name = ?, updated_at = NOW(), updated_by = ?"
                    + " WHERE name = ?",
                target, actor, name
            );
            RepoLifecycleDao.update(
                conn, "UPDATE storage_aliases SET repo_name = ? WHERE repo_name = ?", target, name
            );
            RepoLifecycleDao.update(
                conn, "UPDATE artifact_cooldowns SET repo_name = ? WHERE repo_name = ?",
                target, name
            );
            RepoLifecycleDao.update(
                conn, "UPDATE import_sessions SET repo_name = ? WHERE repo_name = ?", target, name
            );
            final List<String> groups = RepoLifecycleDao.rewriteGroups(conn, name, target);
            final List<String> roles = new RoleGrants().rename(conn, name, target);
            final Duration keep = aliasDays > 0
                ? Duration.ofDays(aliasDays) : RepoLifecycleDao.EVENT_ALIAS;
            try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO repository_aliases (alias, repo_key, routable, expires_at, created_by)"
                    + " VALUES (?, ?, ?, NOW() + (? * INTERVAL '1 millisecond'), ?)"
            )) {
                stmt.setString(1, name);
                stmt.setString(2, row.key());
                stmt.setBoolean(3, aliasDays > 0);
                stmt.setLong(4, keep.toMillis());
                stmt.setString(5, actor);
                stmt.executeUpdate();
            }
            final long job = RepoJobDao.insert(
                conn, RepoJob.Type.RENAME_FOLLOWUP, row.key(), target,
                Json.createObjectBuilder().add("old_name", name).add("new_name", target).build(),
                Duration.ZERO, actor, null
            ).id();
            return new Renamed(row.key(), groups, roles, job);
        });
    }

    /**
     * Delete a repository: it stops existing at once, its data is purged by
     * a background job after the restore window.
     *
     * @param name Repository name
     * @param force Remove it from the groups it is a member of instead of
     *  refusing
     * @param retention Restore window before the purge starts
     * @param actor Who deletes it, nullable
     * @param trace Trace id of the request, nullable
     * @return The tombstone and the purge job
     * @throws RepoOpRejected When the repository is missing, or a member of a
     *  group and {@code force} is not set
     */
    public Deleted delete(
        final String name, final boolean force, final Duration retention,
        final String actor, final String trace
    ) {
        return this.inTransaction(conn -> {
            final Row row = RepoLifecycleDao.lockRepository(conn, name)
                .orElseThrow(
                    () -> new RepoOpRejected(
                        404, "NOT_FOUND", String.format("Repository '%s' not found", name)
                    )
                );
            final List<GroupRef> groups = RepoLifecycleDao.groupsOf(conn, name);
            if (!groups.isEmpty() && !force) {
                final List<String> names = new ArrayList<>(groups.size());
                groups.forEach(ref -> names.add(ref.group()));
                throw new RepoOpRejected(
                    409, "CONFLICT",
                    String.format(
                        "Repository '%s' is a member of group(s) %s; remove it from them or delete with force=true",
                        name, String.join(", ", names)
                    )
                );
            }
            final JsonObjectBuilder extras = Json.createObjectBuilder();
            final JsonArrayBuilder refs = Json.createArrayBuilder();
            for (final GroupRef ref : groups) {
                RepoLifecycleDao.removeMember(conn, ref.group(), ref.config(), name);
                refs.add(
                    Json.createObjectBuilder().add("group", ref.group()).add("index", ref.index())
                );
            }
            extras.add("groups", refs);
            extras.add("storage_aliases", RepoLifecycleDao.storageAliases(conn, name));
            extras.add("cooldown_exemptions", RepoLifecycleDao.exemptions(conn, name));
            final JsonArray grants = new RoleGrants().detach(conn, name);
            extras.add("role_grants", grants);
            try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO repository_tombstones"
                    + " (repo_key, name, type, config, storage_config, extras, deleted_by, purge_after)"
                    + " VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?,"
                    + " NOW() + (? * INTERVAL '1 millisecond'))"
            )) {
                stmt.setString(1, row.key());
                stmt.setString(2, name);
                stmt.setString(3, row.type());
                stmt.setString(4, row.config().toString());
                final Optional<JsonObject> storage =
                    RepoLifecycleDao.storageConfig(conn, name, row.config());
                stmt.setString(5, storage.map(JsonObject::toString).orElse(null));
                stmt.setString(6, extras.build().toString());
                stmt.setString(7, actor);
                stmt.setLong(8, retention.toMillis());
                stmt.executeUpdate();
            }
            RepoLifecycleDao.dropNameLeftovers(conn, name);
            RepoLifecycleDao.update(
                conn, "DELETE FROM repository_aliases WHERE repo_key = ?", row.key()
            );
            RepoLifecycleDao.update(conn, "DELETE FROM repositories WHERE name = ?", name);
            final RepoJobDao.Queued queued = RepoJobDao.insert(
                conn, RepoJob.Type.PURGE, row.key(), name,
                JsonValue.EMPTY_JSON_OBJECT, retention, actor, trace
            );
            final long job = queued.id();
            final Instant purge = queued.start();
            final List<String> affected = new ArrayList<>(groups.size());
            groups.forEach(ref -> affected.add(ref.group()));
            return new Deleted(row.key(), job, purge, affected, new RoleGrants().roles(grants));
        });
    }

    /**
     * Undo a delete whose purge has not started.
     *
     * @param key Key of the deleted repository
     * @param target Name to restore it under, {@code null} for its old name
     * @param actor Who restores it, nullable
     * @return What was restored
     * @throws RepoOpRejected When there is no such tombstone, the purge has
     *  started, or the name is taken
     */
    @SuppressWarnings("PMD.CognitiveComplexity")
    public Restored restore(final String key, final String target, final String actor) {
        return this.inTransaction(conn -> {
            final Tombstone tomb = RepoLifecycleDao.lockTombstone(conn, key)
                .orElseThrow(
                    () -> new RepoOpRejected(
                        404, "NOT_FOUND",
                        String.format("No deleted repository with key '%s'", key)
                    )
                );
            try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT id, phase FROM repo_jobs WHERE repo_key = ? AND type = 'purge'"
                    + " AND state IN ('pending', 'running', 'failed') FOR UPDATE"
            )) {
                stmt.setString(1, key);
                try (ResultSet res = stmt.executeQuery()) {
                    while (res.next()) {
                        if (!RepoJob.PHASE_WAITING.equals(res.getString("phase"))) {
                            throw new RepoOpRejected(
                                409, "CONFLICT",
                                String.format(
                                    "The data of '%s' is already being purged; it cannot be restored",
                                    tomb.name()
                                )
                            );
                        }
                    }
                }
            }
            final String name = target == null || target.isBlank() ? tomb.name() : target;
            RepoLifecycleDao.requireNameFree(conn, name, key);
            RepoLifecycleDao.dropNameLeftovers(conn, name);
            RepoLifecycleDao.update(conn, "DELETE FROM repository_aliases WHERE alias = ?", name);
            try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO repositories (name, repo_key, type, config, created_by, updated_by)"
                    + " VALUES (?, ?, ?, ?::jsonb, ?, ?)"
            )) {
                stmt.setString(1, name);
                stmt.setString(2, key);
                stmt.setString(3, tomb.type());
                stmt.setString(4, tomb.config().toString());
                stmt.setString(5, tomb.deletedBy());
                stmt.setString(6, actor);
                stmt.executeUpdate();
            }
            final List<String> roles = RepoLifecycleDao.reattach(conn, name, tomb.extras());
            RepoLifecycleDao.update(
                conn,
                "UPDATE repo_jobs SET state = 'cancelled', finished_at = NOW(), updated_at = NOW(),"
                    + " lease_owner = NULL, lease_until = NULL, last_error = 'restored'"
                    + " WHERE repo_key = ? AND type = 'purge'"
                    + " AND state IN ('pending', 'running', 'failed')",
                key
            );
            RepoLifecycleDao.update(
                conn, "DELETE FROM repository_tombstones WHERE repo_key = ?", key
            );
            final List<String> groups = new ArrayList<>();
            for (final JsonValue item : RepoLifecycleDao.array(tomb.extras(), "groups")) {
                groups.add(item.asJsonObject().getString("group"));
            }
            return new Restored(name, groups, roles);
        });
    }

    /**
     * Take a name for a new repository: refuse it while it still serves
     * another repository as a former name, and forget former names that
     * only translated queued events.
     * @param name Wanted name
     * @throws RepoOpRejected When the name still routes to another repository
     */
    public void claimName(final String name) {
        this.inTransaction(conn -> {
            RepoLifecycleDao.requireAliasFree(conn, name, "");
            RepoLifecycleDao.update(conn, "DELETE FROM repository_aliases WHERE alias = ?", name);
            // Cooldown blocks and import sessions left by a repository
            // deleted before 2.2.14 must not attach to the new one. Storage
            // aliases are kept: an operator may define one for the name
            // before creating the repository.
            RepoLifecycleDao.update(
                conn, "DELETE FROM artifact_cooldowns WHERE repo_name = ?", name
            );
            RepoLifecycleDao.update(conn, "DELETE FROM import_sessions WHERE repo_name = ?", name);
            return null;
        });
    }

    /**
     * Resolved storage config a repository config points at.
     * @param name Repository name (for its own storage aliases)
     * @param config Repository config
     * @return Storage config, empty for a group or an alias the database
     *  does not know
     */
    public Optional<JsonObject> storageConfigFor(final String name, final JsonObject config) {
        try (Connection conn = this.source.getConnection()) {
            return RepoLifecycleDao.storageConfig(conn, name, config);
        } catch (final SQLException ex) {
            throw new IllegalStateException("Failed to resolve storage of " + name, ex);
        }
    }

    /**
     * Deleted repositories whose data is not purged yet.
     * @return Tombstones, newest first
     */
    public List<Tombstone> tombstones() {
        final List<Tombstone> res = new ArrayList<>();
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT repo_key, name, type, config, storage_config, extras, deleted_at,"
                    + " deleted_by, purge_after, " + RepoLifecycleDao.AGE
                    + " FROM repository_tombstones ORDER BY deleted_at DESC"
            );
            ResultSet rows = stmt.executeQuery()) {
            while (rows.next()) {
                res.add(RepoLifecycleDao.tombstone(rows));
            }
        } catch (final SQLException ex) {
            throw new IllegalStateException("Failed to list deleted repositories", ex);
        }
        return res;
    }

    /**
     * A deleted repository.
     * @param key Repository key
     * @return Tombstone, empty when purged or never deleted
     */
    public Optional<Tombstone> tombstone(final String key) {
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT repo_key, name, type, config, storage_config, extras, deleted_at,"
                    + " deleted_by, purge_after, " + RepoLifecycleDao.AGE
                    + " FROM repository_tombstones WHERE repo_key = ?"
            )) {
            stmt.setString(1, key);
            try (ResultSet rows = stmt.executeQuery()) {
                return rows.next()
                    ? Optional.of(RepoLifecycleDao.tombstone(rows)) : Optional.empty();
            }
        } catch (final SQLException ex) {
            throw new IllegalStateException("Failed to read deleted repository " + key, ex);
        }
    }

    /**
     * Remove a tombstone once its data is purged: the key is free again.
     * @param key Repository key
     */
    public void forget(final String key) {
        try (Connection conn = this.source.getConnection()) {
            RepoLifecycleDao.update(
                conn, "DELETE FROM repository_tombstones WHERE repo_key = ?", key
            );
        } catch (final SQLException ex) {
            throw new IllegalStateException("Failed to remove tombstone " + key, ex);
        }
    }

    /**
     * Every key in use: live repositories and tombstones.
     * @return Keys
     */
    public List<String> keysInUse() {
        final List<String> res = new ArrayList<>();
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT repo_key FROM repositories UNION SELECT repo_key FROM repository_tombstones"
            );
            ResultSet rows = stmt.executeQuery()) {
            while (rows.next()) {
                res.add(rows.getString(1));
            }
        } catch (final SQLException ex) {
            throw new IllegalStateException("Failed to list repository keys", ex);
        }
        return res;
    }

    /**
     * A key no repository and no tombstone holds, derived from a name.
     * @param name Repository name
     * @param taken Extra keys that must not be used (leftover directories)
     * @return Free key
     */
    public String freeKey(final String name, final java.util.Set<String> taken) {
        final java.util.Set<String> used = new java.util.HashSet<>(this.keysInUse());
        used.addAll(taken);
        String candidate = name;
        int suffix = 1;
        while (used.contains(candidate)) {
            suffix += 1;
            candidate = String.format("%s~%d", name, suffix);
        }
        return candidate;
    }

    /**
     * Run a unit of work in one transaction.
     * @param work Work
     * @param <T> Result type
     * @return Result of the work
     */
    private <T> T inTransaction(final Work<T> work) {
        try (Connection conn = this.source.getConnection()) {
            final boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                final T res = work.run(conn);
                conn.commit();
                return res;
            } catch (final SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(auto);
            }
        } catch (final SQLException ex) {
            throw new IllegalStateException("Repository operation failed", ex);
        }
    }

    /**
     * Lock a repository row.
     * @param conn Connection
     * @param name Repository name
     * @return Row, empty when there is no such repository
     * @throws SQLException On error
     */
    private static Optional<Row> lockRepository(final Connection conn, final String name)
        throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
            "SELECT repo_key, type, config FROM repositories WHERE name = ? FOR UPDATE"
        )) {
            stmt.setString(1, name);
            try (ResultSet res = stmt.executeQuery()) {
                return res.next()
                    ? Optional.of(
                        new Row(
                            res.getString("repo_key"), res.getString("type"),
                            RepoLifecycleDao.json(res.getString("config"))
                        )
                    )
                    : Optional.empty();
            }
        }
    }

    /**
     * Lock a tombstone row.
     * @param conn Connection
     * @param key Repository key
     * @return Tombstone, empty when there is none
     * @throws SQLException On error
     */
    private static Optional<Tombstone> lockTombstone(final Connection conn, final String key)
        throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
            "SELECT repo_key, name, type, config, storage_config, extras, deleted_at,"
                + " deleted_by, purge_after, " + RepoLifecycleDao.AGE
                + " FROM repository_tombstones WHERE repo_key = ? FOR UPDATE"
        )) {
            stmt.setString(1, key);
            try (ResultSet res = stmt.executeQuery()) {
                return res.next()
                    ? Optional.of(RepoLifecycleDao.tombstone(res)) : Optional.empty();
            }
        }
    }

    /**
     * Refuse a name that a repository has, or that still routes to another
     * repository as a former name.
     * @param conn Connection
     * @param name Wanted name
     * @param key Key of the repository that wants it
     * @throws SQLException On error
     */
    private static void requireNameFree(final Connection conn, final String name, final String key)
        throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
            "SELECT 1 FROM repositories WHERE name = ?"
        )) {
            stmt.setString(1, name);
            try (ResultSet res = stmt.executeQuery()) {
                if (res.next()) {
                    throw new RepoOpRejected(
                        409, "CONFLICT", String.format("Repository '%s' already exists", name)
                    );
                }
            }
        }
        RepoLifecycleDao.requireAliasFree(conn, name, key);
    }

    /**
     * Refuse a name that still routes to another repository as a former
     * name.
     * @param conn Connection
     * @param name Wanted name
     * @param key Key of the repository that wants it (its own former name
     *  is its to take back)
     * @throws SQLException On error
     */
    private static void requireAliasFree(final Connection conn, final String name, final String key)
        throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
            "SELECT a.expires_at, r.name FROM repository_aliases a"
                + " JOIN repositories r ON r.repo_key = a.repo_key"
                + " WHERE a.alias = ? AND a.routable AND a.expires_at > NOW() AND a.repo_key <> ?"
        )) {
            stmt.setString(1, name);
            stmt.setString(2, key);
            try (ResultSet res = stmt.executeQuery()) {
                if (res.next()) {
                    throw new RepoOpRejected(
                        409, "CONFLICT",
                        String.format(
                            "'%s' is the former name of repository '%s' and keeps serving it until %s",
                            name, res.getString(2), res.getTimestamp(1).toInstant()
                        )
                    );
                }
            }
        }
    }

    /**
     * Drop the name-keyed records left under a name that no repository
     * holds: live cooldown blocks, import sessions and repository-scoped
     * storage aliases. A repository that takes the name starts clean.
     * @param conn Connection
     * @param name Repository name
     * @throws SQLException On error
     */
    private static void dropNameLeftovers(final Connection conn, final String name)
        throws SQLException {
        RepoLifecycleDao.update(conn, "DELETE FROM artifact_cooldowns WHERE repo_name = ?", name);
        RepoLifecycleDao.update(conn, "DELETE FROM import_sessions WHERE repo_name = ?", name);
        RepoLifecycleDao.update(conn, "DELETE FROM storage_aliases WHERE repo_name = ?", name);
    }

    /**
     * Groups that list a repository as a member.
     * @param conn Connection
     * @param name Member name
     * @return Groups with their config and the member's position
     * @throws SQLException On error
     */
    private static List<GroupRef> groupsOf(final Connection conn, final String name)
        throws SQLException {
        final List<GroupRef> res = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(
            "SELECT name, config FROM repositories"
                + " WHERE jsonb_exists(config -> 'repo' -> 'members', ?) ORDER BY name FOR UPDATE"
        )) {
            stmt.setString(1, name);
            try (ResultSet rows = stmt.executeQuery()) {
                while (rows.next()) {
                    final JsonObject config = RepoLifecycleDao.json(rows.getString("config"));
                    final int index = RepoLifecycleDao.members(config).indexOf(name);
                    if (index >= 0) {
                        res.add(new GroupRef(rows.getString("name"), config, index));
                    }
                }
            }
        }
        return res;
    }

    /**
     * Replace a member name in every group that lists it.
     * @param conn Connection
     * @param name Old member name
     * @param target New member name
     * @return Names of the groups rewritten
     * @throws SQLException On error
     */
    private static List<String> rewriteGroups(
        final Connection conn, final String name, final String target
    ) throws SQLException {
        final List<String> res = new ArrayList<>();
        for (final GroupRef ref : RepoLifecycleDao.groupsOf(conn, name)) {
            final List<String> members = RepoLifecycleDao.members(ref.config());
            members.set(ref.index(), target);
            RepoLifecycleDao.saveMembers(conn, ref.group(), ref.config(), members);
            res.add(ref.group());
        }
        return res;
    }

    /**
     * Remove a member from a group.
     * @param conn Connection
     * @param group Group name
     * @param config Group config
     * @param member Member to remove
     * @throws SQLException On error
     */
    private static void removeMember(
        final Connection conn, final String group, final JsonObject config, final String member
    ) throws SQLException {
        final List<String> members = RepoLifecycleDao.members(config);
        members.remove(member);
        RepoLifecycleDao.saveMembers(conn, group, config, members);
    }

    /**
     * Member names of a group config.
     * @param config Repository config
     * @return Mutable list of member names, empty for a non-group
     */
    private static List<String> members(final JsonObject config) {
        final List<String> res = new ArrayList<>();
        final JsonObject repo = config.getJsonObject(RepoLifecycleDao.REPO);
        if (repo != null && repo.containsKey(RepoLifecycleDao.MEMBERS)
            && repo.get(RepoLifecycleDao.MEMBERS).getValueType() == JsonValue.ValueType.ARRAY) {
            for (final JsonValue item : repo.getJsonArray(RepoLifecycleDao.MEMBERS)) {
                if (item.getValueType() == JsonValue.ValueType.STRING) {
                    res.add(((JsonString) item).getString());
                }
            }
        }
        return res;
    }

    /**
     * Write a group's member list.
     * @param conn Connection
     * @param group Group name
     * @param config Group config
     * @param members New member names
     * @throws SQLException On error
     */
    private static void saveMembers(
        final Connection conn, final String group, final JsonObject config,
        final List<String> members
    ) throws SQLException {
        final JsonArrayBuilder list = Json.createArrayBuilder();
        members.forEach(list::add);
        final JsonObject repo = Json.createObjectBuilder(config.getJsonObject(RepoLifecycleDao.REPO))
            .add(RepoLifecycleDao.MEMBERS, list).build();
        final JsonObject updated = Json.createObjectBuilder(config)
            .add(RepoLifecycleDao.REPO, repo).build();
        try (PreparedStatement stmt = conn.prepareStatement(
            "UPDATE repositories SET config = ?::jsonb, updated_at = NOW() WHERE name = ?"
        )) {
            stmt.setString(1, updated.toString());
            stmt.setString(2, group);
            stmt.executeUpdate();
        }
    }

    /**
     * Resolved config of a repository's storage: its inline mapping, or the
     * config of the alias it names (its own alias first, then the global).
     * @param conn Connection
     * @param name Repository name
     * @param config Repository config
     * @return Storage config, empty for a repository without storage (a
     *  group) or with an alias the database does not know
     * @throws SQLException On error
     */
    private static Optional<JsonObject> storageConfig(
        final Connection conn, final String name, final JsonObject config
    ) throws SQLException {
        final JsonObject repo = config.getJsonObject(RepoLifecycleDao.REPO);
        Optional<JsonObject> res = Optional.empty();
        if (repo != null && repo.containsKey("storage")) {
            final JsonValue storage = repo.get("storage");
            if (storage.getValueType() == JsonValue.ValueType.OBJECT) {
                res = Optional.of(storage.asJsonObject());
            } else if (storage.getValueType() == JsonValue.ValueType.STRING) {
                try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT config FROM storage_aliases WHERE name = ?"
                        + " AND (repo_name = ? OR repo_name IS NULL)"
                        + " ORDER BY repo_name NULLS LAST LIMIT 1"
                )) {
                    stmt.setString(1, ((JsonString) storage).getString());
                    stmt.setString(2, name);
                    try (ResultSet rows = stmt.executeQuery()) {
                        if (rows.next()) {
                            res = Optional.of(RepoLifecycleDao.json(rows.getString(1)));
                        }
                    }
                }
            }
        }
        return res;
    }

    /**
     * Repository-scoped storage aliases of a repository.
     * @param conn Connection
     * @param name Repository name
     * @return Alias records
     * @throws SQLException On error
     */
    private static JsonArray storageAliases(final Connection conn, final String name)
        throws SQLException {
        final JsonArrayBuilder res = Json.createArrayBuilder();
        try (PreparedStatement stmt = conn.prepareStatement(
            "SELECT name, config FROM storage_aliases WHERE repo_name = ?"
        )) {
            stmt.setString(1, name);
            try (ResultSet rows = stmt.executeQuery()) {
                while (rows.next()) {
                    res.add(
                        Json.createObjectBuilder()
                            .add("name", rows.getString(1))
                            .add("config", RepoLifecycleDao.json(rows.getString(2)))
                    );
                }
            }
        }
        return res.build();
    }

    /**
     * Cooldown exemptions of a repository.
     * @param conn Connection
     * @param name Repository name
     * @return Exemption records
     * @throws SQLException On error
     */
    private static JsonArray exemptions(final Connection conn, final String name)
        throws SQLException {
        final JsonArrayBuilder res = Json.createArrayBuilder();
        try (PreparedStatement stmt = conn.prepareStatement(
            "SELECT pattern, expires_at, reason, created_by FROM cooldown_exemptions"
                + " WHERE repo_name = ?"
        )) {
            stmt.setString(1, name);
            try (ResultSet rows = stmt.executeQuery()) {
                while (rows.next()) {
                    final JsonObjectBuilder item = Json.createObjectBuilder()
                        .add("pattern", rows.getString(1))
                        .add("created_by", rows.getString(4));
                    final Timestamp expires = rows.getTimestamp(2);
                    if (expires != null) {
                        item.add("expires_at", expires.toInstant().toString());
                    }
                    if (rows.getString(3) != null) {
                        item.add("reason", rows.getString(3));
                    }
                    res.add(item);
                }
            }
        }
        return res.build();
    }

    /**
     * Give a restored repository back what the delete took from it: its
     * storage aliases, cooldown exemptions, role grants and group
     * memberships (in groups that still exist).
     * @param conn Connection
     * @param name Name the repository is restored under
     * @param extras What the delete saved
     * @return Names of the roles whose grants changed
     * @throws SQLException On error
     */
    private static List<String> reattach(final Connection conn, final String name, final JsonObject extras)
        throws SQLException {
        for (final JsonValue item : RepoLifecycleDao.array(extras, "storage_aliases")) {
            final JsonObject alias = item.asJsonObject();
            try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO storage_aliases (name, repo_name, config) VALUES (?, ?, ?::jsonb)"
                    + " ON CONFLICT (name, repo_name) DO NOTHING"
            )) {
                stmt.setString(1, alias.getString("name"));
                stmt.setString(2, name);
                stmt.setString(3, alias.getJsonObject("config").toString());
                stmt.executeUpdate();
            }
        }
        for (final JsonValue item : RepoLifecycleDao.array(extras, "cooldown_exemptions")) {
            final JsonObject exempt = item.asJsonObject();
            try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO cooldown_exemptions (repo_name, pattern, expires_at, reason, created_by)"
                    + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (repo_name, pattern) DO NOTHING"
            )) {
                stmt.setString(1, name);
                stmt.setString(2, exempt.getString("pattern"));
                stmt.setTimestamp(
                    3,
                    exempt.containsKey("expires_at")
                        ? Timestamp.from(Instant.parse(exempt.getString("expires_at"))) : null
                );
                stmt.setString(4, exempt.getString("reason", null));
                stmt.setString(5, exempt.getString("created_by", "restore"));
                stmt.executeUpdate();
            }
        }
        final List<String> roles =
            new RoleGrants().attach(conn, name, RepoLifecycleDao.array(extras, "role_grants"));
        for (final JsonValue item : RepoLifecycleDao.array(extras, "groups")) {
            final JsonObject ref = item.asJsonObject();
            RepoLifecycleDao.rejoin(conn, ref.getString("group"), name, ref.getInt("index", -1));
        }
        return roles;
    }

    /**
     * Put a restored repository back into a group, at its old position when
     * the list still has one.
     * @param conn Connection
     * @param group Group name
     * @param member Member name
     * @param index Old position
     * @throws SQLException On error
     */
    private static void rejoin(
        final Connection conn, final String group, final String member, final int index
    ) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
            "SELECT config FROM repositories WHERE name = ? FOR UPDATE"
        )) {
            stmt.setString(1, group);
            try (ResultSet rows = stmt.executeQuery()) {
                if (rows.next()) {
                    final JsonObject config = RepoLifecycleDao.json(rows.getString(1));
                    final List<String> members = RepoLifecycleDao.members(config);
                    final JsonObject repo = config.getJsonObject(RepoLifecycleDao.REPO);
                    if (repo != null && repo.containsKey(RepoLifecycleDao.MEMBERS)
                        && !members.contains(member)) {
                        if (index >= 0 && index <= members.size()) {
                            members.add(index, member);
                        } else {
                            members.add(member);
                        }
                        RepoLifecycleDao.saveMembers(conn, group, config, members);
                    }
                }
            }
        }
    }

    /**
     * Array member of an object, empty when absent.
     * @param obj Object
     * @param name Member name
     * @return Array
     */
    private static JsonArray array(final JsonObject obj, final String name) {
        return obj.containsKey(name) && obj.get(name).getValueType() == JsonValue.ValueType.ARRAY
            ? obj.getJsonArray(name) : JsonValue.EMPTY_JSON_ARRAY;
    }

    /**
     * Map a tombstone row.
     * @param rows Result set positioned on a row
     * @return Tombstone
     * @throws SQLException On error
     */
    private static Tombstone tombstone(final ResultSet rows) throws SQLException {
        final String storage = rows.getString("storage_config");
        return new Tombstone(
            rows.getString("repo_key"), rows.getString("name"), rows.getString("type"),
            RepoLifecycleDao.json(rows.getString("config")),
            storage == null ? Optional.empty() : Optional.of(RepoLifecycleDao.json(storage)),
            RepoLifecycleDao.json(rows.getString("extras")),
            rows.getTimestamp("deleted_at").toInstant(), rows.getString("deleted_by"),
            rows.getTimestamp("purge_after").toInstant(),
            Duration.ofMillis(Math.max(0L, rows.getLong("age_ms")))
        );
    }

    /**
     * Run an update with string parameters.
     * @param conn Connection
     * @param sql Statement
     * @param params Parameters
     * @throws SQLException On error
     */
    private static void update(final Connection conn, final String sql, final String... params)
        throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int idx = 0; idx < params.length; idx += 1) {
                stmt.setString(idx + 1, params[idx]);
            }
            stmt.executeUpdate();
        }
    }

    /**
     * Parse a JSON object.
     * @param text JSON text
     * @return Object
     */
    private static JsonObject json(final String text) {
        return Json.createReader(new StringReader(text)).readObject();
    }

    /**
     * Unit of work inside a transaction.
     * @param <T> Result type
     */
    @FunctionalInterface
    private interface Work<T> {
        /**
         * Run.
         * @param conn Connection with auto-commit off
         * @return Result
         * @throws SQLException On error
         */
        T run(Connection conn) throws SQLException;
    }

    /**
     * A locked repository row.
     * @param key Repository key
     * @param type Repository type
     * @param config Repository config
     */
    private record Row(String key, String type, JsonObject config) {
    }

    /**
     * A group that lists a repository.
     * @param group Group name
     * @param config Group config
     * @param index Position of the member in the group's list
     */
    private record GroupRef(String group, JsonObject config, int index) {
    }

    /**
     * Outcome of a rename.
     * @param key Repository key
     * @param groups Groups whose member list was rewritten
     * @param roles Roles whose grants were moved to the new name
     * @param job Follow-up job id
     */
    public record Renamed(String key, List<String> groups, List<String> roles, long job) {
    }

    /**
     * Outcome of a delete.
     * @param key Repository key
     * @param job Purge job id
     * @param purgeAfter When the purge may start
     * @param groups Groups the repository was removed from
     * @param roles Roles whose grants on the repository were taken out
     */
    public record Deleted(
        String key, long job, Instant purgeAfter, List<String> groups, List<String> roles
    ) {
    }

    /**
     * Outcome of a restore.
     * @param name Name the repository was restored under
     * @param groups Groups it was put back into
     * @param roles Roles that got their grants back
     */
    public record Restored(String name, List<String> groups, List<String> roles) {
    }

    /**
     * A deleted repository waiting for its purge.
     * @param key Repository key
     * @param name Name it had
     * @param type Repository type
     * @param config Its config
     * @param storage Resolved config of its storage, empty without storage
     * @param extras What the delete detached (aliases, exemptions, grants, groups)
     * @param deletedAt When it was deleted
     * @param deletedBy Who deleted it, nullable
     * @param purgeAfter End of the restore window
     * @param age Time since the delete, by the database clock
     */
    public record Tombstone(
        String key, String name, String type, JsonObject config, Optional<JsonObject> storage,
        JsonObject extras, Instant deletedAt, String deletedBy, Instant purgeAfter, Duration age
    ) {
    }
}
