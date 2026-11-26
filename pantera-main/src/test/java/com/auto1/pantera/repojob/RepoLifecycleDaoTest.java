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

import com.auto1.pantera.db.PostgreSQLTestConfig;
import java.time.Duration;
import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.collection.IsEmptyCollection;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNull;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests for {@link RepoLifecycleDao}: rename, delete and restore are
 * metadata transactions that keep the repository key, and with it the data,
 * where it is.
 *
 * @since 2.2.14
 */
@Testcontainers
@SuppressWarnings("PMD.TooManyMethods")
final class RepoLifecycleDaoTest {

    /**
     * PostgreSQL.
     */
    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();

    /**
     * Database helper.
     */
    private static RepoJobsDb db;

    /**
     * Subject.
     */
    private RepoLifecycleDao dao;

    @BeforeAll
    static void migrate() {
        RepoLifecycleDaoTest.db = new RepoJobsDb(RepoLifecycleDaoTest.PG);
    }

    @AfterAll
    static void close() {
        RepoLifecycleDaoTest.db.close();
    }

    @BeforeEach
    void init() {
        RepoLifecycleDaoTest.db.clean();
        this.dao = new RepoLifecycleDao(RepoLifecycleDaoTest.db.source());
    }

    @Test
    void existingRepositoriesGetTheirNameAsKey() {
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        MatcherAssert.assertThat(
            RepoLifecycleDaoTest.db.value("SELECT repo_key FROM repositories WHERE name = 'libs'"),
            new IsEqual<>("libs")
        );
    }

    @Test
    void renameChangesTheNameAndKeepsTheKey() {
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        RepoLifecycleDaoTest.db.artifact("libs", "com/acme/app", "1.0");
        final RepoLifecycleDao.Renamed renamed = this.dao.rename("libs", "releases", 0, "admin");
        MatcherAssert.assertThat(
            "the key is unchanged", renamed.key(), new IsEqual<>("libs")
        );
        MatcherAssert.assertThat(
            "the row carries the new name under the old key",
            RepoLifecycleDaoTest.db.value("SELECT name FROM repositories WHERE repo_key = 'libs'"),
            new IsEqual<>("releases")
        );
        MatcherAssert.assertThat(
            "no index row was rewritten: rows stay under the key",
            RepoLifecycleDaoTest.db.count("SELECT COUNT(*) FROM artifacts WHERE repo_name = 'libs'"),
            new IsEqual<>(1L)
        );
        MatcherAssert.assertThat(
            "the former name is remembered for queued events, not routed",
            RepoLifecycleDaoTest.db.value(
                "SELECT routable::text FROM repository_aliases WHERE alias = 'libs'"
            ),
            new IsEqual<>("false")
        );
        MatcherAssert.assertThat(
            "the history follow-up is queued",
            RepoLifecycleDaoTest.db.value(
                "SELECT type FROM repo_jobs WHERE id = " + renamed.job()
            ),
            new IsEqual<>("rename_followup")
        );
    }

    @Test
    void renameCanKeepTheOldNameRoutable() {
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        this.dao.rename("libs", "releases", 30, "admin");
        MatcherAssert.assertThat(
            RepoLifecycleDaoTest.db.value(
                "SELECT (routable AND expires_at > NOW() + INTERVAL '29 days')::text"
                    + " FROM repository_aliases WHERE alias = 'libs'"
            ),
            new IsEqual<>("true")
        );
    }

    @Test
    void renameMovesNameKeyedRecords() {
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        RepoLifecycleDaoTest.db.group("all", "central", "libs");
        RepoLifecycleDaoTest.db.exec(
            "INSERT INTO roles (name, permissions) VALUES ('test-dev', ?::jsonb)",
            "{\"adapter_basic_permissions\":{\"libs\":[\"read\"],\"maven-*\":[\"read\"]},"
                + "\"api_search_permissions\":[\"read\"]}"
        );
        RepoLifecycleDaoTest.db.exec(
            "INSERT INTO storage_aliases (name, repo_name, config) VALUES ('fast', 'libs', '{}'::jsonb)"
        );
        RepoLifecycleDaoTest.db.exec(
            "INSERT INTO artifact_cooldowns (repo_type, repo_name, artifact, version, reason,"
                + " status, blocked_by, blocked_at, blocked_until)"
                + " VALUES ('maven', 'libs', 'a', '1', 'FRESH_RELEASE', 'ACTIVE', 'system', 1, 2)"
        );
        RepoLifecycleDaoTest.db.exec(
            "INSERT INTO cooldown_exemptions (repo_name, pattern, created_by)"
                + " VALUES ('libs', 'com.acme.*', 'admin')"
        );
        final RepoLifecycleDao.Renamed renamed = this.dao.rename("libs", "releases", 0, "admin");
        MatcherAssert.assertThat(
            "the group lists the new name in the same position",
            RepoLifecycleDaoTest.db.value(
                "SELECT config -> 'repo' -> 'members' ->> 1 FROM repositories WHERE name = 'all'"
            ),
            new IsEqual<>("releases")
        );
        MatcherAssert.assertThat(
            "the rewritten group is reported", renamed.groups(), new IsEqual<>(List.of("all"))
        );
        MatcherAssert.assertThat(
            "the exact grant moved to the new name, the pattern grant is untouched",
            RepoLifecycleDaoTest.db.value(
                "SELECT (jsonb_exists(permissions -> 'adapter_basic_permissions', 'releases')"
                    + " AND NOT jsonb_exists(permissions -> 'adapter_basic_permissions', 'libs')"
                    + " AND jsonb_exists(permissions -> 'adapter_basic_permissions', 'maven-*'))::text"
                    + " FROM roles WHERE name = 'test-dev'"
            ),
            new IsEqual<>("true")
        );
        MatcherAssert.assertThat(
            "the changed role is reported", renamed.roles(), new IsEqual<>(List.of("test-dev"))
        );
        MatcherAssert.assertThat(
            "the storage alias follows",
            RepoLifecycleDaoTest.db.value("SELECT repo_name FROM storage_aliases WHERE name = 'fast'"),
            new IsEqual<>("releases")
        );
        MatcherAssert.assertThat(
            "live cooldown blocks follow",
            RepoLifecycleDaoTest.db.value("SELECT repo_name FROM artifact_cooldowns"),
            new IsEqual<>("releases")
        );
        MatcherAssert.assertThat(
            "cooldown exemptions follow",
            RepoLifecycleDaoTest.db.value("SELECT repo_name FROM cooldown_exemptions"),
            new IsEqual<>("releases")
        );
    }

    @Test
    void renameTakesTheRoutingRulesOfTheMemberAlong() {
        // A rule that still named the old member would limit nobody: the
        // renamed member would be asked for every path.
        RepoLifecycleDaoTest.db.repo("central", "maven-proxy");
        RepoLifecycleDaoTest.db.group("all", "libs", "central");
        RepoLifecycleDaoTest.routing(
            "all",
            "[{\"member\":\"central\",\"pattern\":\"(?!com/acme/).*\"},"
                + "{\"member\":\"libs\",\"prefix\":\"com/acme/\"},"
                + "{\"member\":\"central\",\"prefix\":\"org/\"}]"
        );
        this.dao.rename("central", "maven-central", 0, "admin");
        MatcherAssert.assertThat(
            RepoLifecycleDaoTest.rules("all"),
            new IsEqual<>(
                "[{\"member\": \"maven-central\", \"pattern\": \"(?!com/acme/).*\"}, "
                    + "{\"member\": \"libs\", \"prefix\": \"com/acme/\"}, "
                    + "{\"member\": \"maven-central\", \"prefix\": \"org/\"}]"
            )
        );
    }

    @Test
    void deleteTakesTheRulesOfTheMemberOutAndRestorePutsThemBack() {
        RepoLifecycleDaoTest.db.repo("central", "maven-proxy");
        RepoLifecycleDaoTest.db.group("all", "libs", "central");
        RepoLifecycleDaoTest.routing(
            "all",
            "[{\"member\":\"central\",\"pattern\":\"(?!com/acme/).*\"},"
                + "{\"member\":\"libs\",\"prefix\":\"com/acme/\"}]"
        );
        this.dao.delete("central", true, Duration.ofHours(1), "admin", null);
        MatcherAssert.assertThat(
            "a rule for a repository that is no longer a member would keep the group from"
                + " being saved: it goes with the member",
            RepoLifecycleDaoTest.rules("all"),
            new IsEqual<>("[{\"member\": \"libs\", \"prefix\": \"com/acme/\"}]")
        );
        this.dao.restore("central", "central-again", "admin");
        MatcherAssert.assertThat(
            "the restored member is limited as before, under the name it came back with",
            RepoLifecycleDaoTest.rules("all"),
            new IsEqual<>(
                "[{\"member\": \"libs\", \"prefix\": \"com/acme/\"}, "
                    + "{\"member\": \"central-again\", \"pattern\": \"(?!com/acme/).*\"}]"
            )
        );
    }

    @Test
    void deleteOfAMemberWithTheOnlyRulesLeavesAGroupWithoutRouting() {
        RepoLifecycleDaoTest.db.repo("central", "maven-proxy");
        RepoLifecycleDaoTest.db.group("all", "libs", "central");
        RepoLifecycleDaoTest.routing("all", "[{\"member\":\"central\",\"prefix\":\"org/\"}]");
        this.dao.delete("central", true, Duration.ofHours(1), "admin", null);
        MatcherAssert.assertThat(
            RepoLifecycleDaoTest.db.value(
                "SELECT jsonb_exists(config -> 'repo', 'routing') FROM repositories WHERE name = 'all'"
            ),
            new IsEqual<>("f")
        );
    }

    private static void routing(final String group, final String rules) {
        RepoLifecycleDaoTest.db.exec(
            "UPDATE repositories SET config = jsonb_set(config, '{repo,routing}', ?::jsonb)"
                + " WHERE name = ?",
            rules, group
        );
    }

    private static String rules(final String group) {
        return RepoLifecycleDaoTest.db.value(
            "SELECT config -> 'repo' ->> 'routing' FROM repositories WHERE name = ?", group
        );
    }

    @Test
    void renameOfMissingRepositoryIsRefused() {
        final RepoOpRejected rejected = Assertions.assertThrows(
            RepoOpRejected.class, () -> this.dao.rename("ghost", "other", 0, "admin")
        );
        MatcherAssert.assertThat(rejected.status(), new IsEqual<>(404));
    }

    @Test
    void renameOntoExistingRepositoryIsRefused() {
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        RepoLifecycleDaoTest.db.repo("releases", "maven");
        final RepoOpRejected rejected = Assertions.assertThrows(
            RepoOpRejected.class, () -> this.dao.rename("libs", "releases", 0, "admin")
        );
        MatcherAssert.assertThat(
            "conflict", rejected.status(), new IsEqual<>(409)
        );
        MatcherAssert.assertThat(
            "nothing changed",
            RepoLifecycleDaoTest.db.count("SELECT COUNT(*) FROM repositories WHERE name = 'libs'"),
            new IsEqual<>(1L)
        );
    }

    @Test
    void renameOntoNameThatStillRoutesToAnotherRepositoryIsRefused() {
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        RepoLifecycleDaoTest.db.repo("other", "maven");
        this.dao.rename("libs", "releases", 7, "admin");
        final RepoOpRejected rejected = Assertions.assertThrows(
            RepoOpRejected.class, () -> this.dao.rename("other", "libs", 0, "admin")
        );
        MatcherAssert.assertThat(
            "conflict", rejected.status(), new IsEqual<>(409)
        );
        MatcherAssert.assertThat(
            "the message names the repository holding the former name",
            rejected.getMessage(), new StringContains("releases")
        );
    }

    @Test
    void repositoryCanBeRenamedBackToItsFormerName() {
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        this.dao.rename("libs", "releases", 7, "admin");
        this.dao.rename("releases", "libs", 0, "admin");
        MatcherAssert.assertThat(
            "the name is back",
            RepoLifecycleDaoTest.db.value("SELECT name FROM repositories WHERE repo_key = 'libs'"),
            new IsEqual<>("libs")
        );
        MatcherAssert.assertThat(
            "only the newer former name is remembered",
            RepoLifecycleDaoTest.db.column("SELECT alias FROM repository_aliases"),
            new IsEqual<>(List.of("releases"))
        );
    }

    @Test
    void renameDropsLeftoversOfAnEarlierRepositoryUnderTheTargetName() {
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        RepoLifecycleDaoTest.db.exec(
            "INSERT INTO artifact_cooldowns (repo_type, repo_name, artifact, version, reason,"
                + " status, blocked_by, blocked_at, blocked_until)"
                + " VALUES ('maven', 'releases', 'stale', '1', 'FRESH_RELEASE', 'ACTIVE', 'system', 1, 2)"
        );
        this.dao.rename("libs", "releases", 0, "admin");
        MatcherAssert.assertThat(
            RepoLifecycleDaoTest.db.count("SELECT COUNT(*) FROM artifact_cooldowns"),
            new IsEqual<>(0L)
        );
    }

    @Test
    void deleteLeavesATombstoneAndFreesTheName() {
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        final RepoLifecycleDao.Deleted deleted =
            this.dao.delete("libs", false, Duration.ofHours(24), "admin", "trace-1");
        MatcherAssert.assertThat(
            "the repository is gone",
            RepoLifecycleDaoTest.db.count("SELECT COUNT(*) FROM repositories"),
            new IsEqual<>(0L)
        );
        final RepoLifecycleDao.Tombstone tomb = this.dao.tombstone("libs").orElseThrow();
        MatcherAssert.assertThat(
            "the tombstone keeps the name", tomb.name(), new IsEqual<>("libs")
        );
        MatcherAssert.assertThat(
            "the tombstone keeps the resolved storage for the purge",
            tomb.storage().orElseThrow().getString("path"), new IsEqual<>("/var/pantera/data")
        );
        MatcherAssert.assertThat(
            "a purge job waits for the restore window",
            RepoLifecycleDaoTest.db.value(
                "SELECT (type = 'purge' AND state = 'pending' AND phase = 'waiting'"
                    + " AND not_before > NOW() + INTERVAL '23 hours' AND trace_id = 'trace-1')::text"
                    + " FROM repo_jobs WHERE id = " + deleted.job()
            ),
            new IsEqual<>("true")
        );
    }

    @Test
    void recreatedNameGetsAFreshKeyWhileTheOldDataIsNotPurged() {
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        this.dao.delete("libs", false, Duration.ZERO, "admin", null);
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        MatcherAssert.assertThat(
            "the new repository does not see the deleted one's directory",
            RepoLifecycleDaoTest.db.value("SELECT repo_key FROM repositories WHERE name = 'libs'"),
            new IsEqual<>("libs~2")
        );
    }

    @Test
    void deleteOfGroupMemberIsRefusedUnlessForced() {
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        RepoLifecycleDaoTest.db.group("all", "libs", "central");
        final RepoOpRejected rejected = Assertions.assertThrows(
            RepoOpRejected.class,
            () -> this.dao.delete("libs", false, Duration.ZERO, "admin", null)
        );
        MatcherAssert.assertThat(
            "conflict naming the group", rejected.getMessage(), new StringContains("all")
        );
        MatcherAssert.assertThat(
            "the repository is kept",
            RepoLifecycleDaoTest.db.count("SELECT COUNT(*) FROM repositories WHERE name = 'libs'"),
            new IsEqual<>(1L)
        );
        final RepoLifecycleDao.Deleted deleted =
            this.dao.delete("libs", true, Duration.ZERO, "admin", null);
        MatcherAssert.assertThat(
            "the forced delete reports the group", deleted.groups(), new IsEqual<>(List.of("all"))
        );
        MatcherAssert.assertThat(
            "the group no longer lists it",
            RepoLifecycleDaoTest.db.value(
                "SELECT config -> 'repo' ->> 'members' FROM repositories WHERE name = 'all'"
            ),
            new IsEqual<>("[\"central\"]")
        );
    }

    @Test
    void deleteDetachesGrantsSoALaterRepositoryDoesNotInheritThem() {
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        RepoLifecycleDaoTest.db.exec(
            "INSERT INTO roles (name, permissions) VALUES ('test-dev', ?::jsonb)",
            "{\"adapter_basic_permissions\":{\"libs\":[\"read\",\"write\"],\"*\":[\"read\"]}}"
        );
        final RepoLifecycleDao.Deleted deleted =
            this.dao.delete("libs", false, Duration.ZERO, "admin", null);
        MatcherAssert.assertThat(
            "the exact grant is gone, the wildcard stays",
            RepoLifecycleDaoTest.db.value(
                "SELECT permissions::text FROM roles WHERE name = 'test-dev'"
            ),
            new IsEqual<>("{\"adapter_basic_permissions\": {\"*\": [\"read\"]}}")
        );
        MatcherAssert.assertThat(
            "the changed role is reported", deleted.roles(), new IsEqual<>(List.of("test-dev"))
        );
    }

    @Test
    void deleteOfMissingRepositoryIsRefused() {
        final RepoOpRejected rejected = Assertions.assertThrows(
            RepoOpRejected.class,
            () -> this.dao.delete("ghost", false, Duration.ZERO, "admin", null)
        );
        MatcherAssert.assertThat(rejected.status(), new IsEqual<>(404));
    }

    @Test
    void secondDeleteOfTheSameRepositoryIsRefused() {
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        this.dao.delete("libs", false, Duration.ZERO, "admin", null);
        final RepoOpRejected rejected = Assertions.assertThrows(
            RepoOpRejected.class,
            () -> this.dao.delete("libs", false, Duration.ZERO, "admin", null)
        );
        MatcherAssert.assertThat(rejected.status(), new IsEqual<>(404));
    }

    @Test
    void restoreBringsEverythingBack() {
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        RepoLifecycleDaoTest.db.group("all", "central", "libs", "extra");
        RepoLifecycleDaoTest.db.exec(
            "INSERT INTO roles (name, permissions) VALUES ('test-dev', ?::jsonb)",
            "{\"adapter_basic_permissions\":{\"libs\":[\"read\"]}}"
        );
        RepoLifecycleDaoTest.db.exec(
            "INSERT INTO storage_aliases (name, repo_name, config) VALUES ('fast', 'libs', '{\"type\":\"fs\"}'::jsonb)"
        );
        RepoLifecycleDaoTest.db.exec(
            "INSERT INTO cooldown_exemptions (repo_name, pattern, created_by)"
                + " VALUES ('libs', 'com.acme.*', 'admin')"
        );
        this.dao.delete("libs", true, Duration.ofHours(1), "admin", null);
        final RepoLifecycleDao.Restored restored = this.dao.restore("libs", null, "admin");
        MatcherAssert.assertThat(
            "restored under its name and key",
            RepoLifecycleDaoTest.db.value("SELECT repo_key FROM repositories WHERE name = 'libs'"),
            new IsEqual<>("libs")
        );
        MatcherAssert.assertThat(
            "back in its group at its old position",
            RepoLifecycleDaoTest.db.value(
                "SELECT config -> 'repo' -> 'members' ->> 1 FROM repositories WHERE name = 'all'"
            ),
            new IsEqual<>("libs")
        );
        MatcherAssert.assertThat(
            "the rejoined group is reported", restored.groups(), new IsEqual<>(List.of("all"))
        );
        MatcherAssert.assertThat(
            "the grant is back",
            RepoLifecycleDaoTest.db.value(
                "SELECT jsonb_exists(permissions -> 'adapter_basic_permissions', 'libs')::text"
                    + " FROM roles WHERE name = 'test-dev'"
            ),
            new IsEqual<>("true")
        );
        MatcherAssert.assertThat(
            "the storage alias is back",
            RepoLifecycleDaoTest.db.count("SELECT COUNT(*) FROM storage_aliases WHERE repo_name = 'libs'"),
            new IsEqual<>(1L)
        );
        MatcherAssert.assertThat(
            "the cooldown exemption is back",
            RepoLifecycleDaoTest.db.count("SELECT COUNT(*) FROM cooldown_exemptions WHERE repo_name = 'libs'"),
            new IsEqual<>(1L)
        );
        MatcherAssert.assertThat(
            "the purge is cancelled",
            RepoLifecycleDaoTest.db.column("SELECT state FROM repo_jobs WHERE type = 'purge'"),
            new IsEqual<>(List.of("cancelled"))
        );
        MatcherAssert.assertThat(
            "the tombstone is gone", this.dao.tombstones(), new IsEmptyCollection<>()
        );
    }

    @Test
    void restoreIsRefusedOnceThePurgeHasStarted() {
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        this.dao.delete("libs", false, Duration.ZERO, "admin", null);
        new RepoJobDao(RepoLifecycleDaoTest.db.source()).claim("node-a", Duration.ofMinutes(1));
        final RepoOpRejected rejected = Assertions.assertThrows(
            RepoOpRejected.class, () -> this.dao.restore("libs", null, "admin")
        );
        MatcherAssert.assertThat(
            "conflict", rejected.status(), new IsEqual<>(409)
        );
        MatcherAssert.assertThat(
            "the repository stays deleted",
            RepoLifecycleDaoTest.db.count("SELECT COUNT(*) FROM repositories"),
            new IsEqual<>(0L)
        );
    }

    @Test
    void restoreUnderATakenNameIsRefusedAndAnotherNameWorks() {
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        this.dao.delete("libs", false, Duration.ofHours(1), "admin", null);
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        final RepoOpRejected rejected = Assertions.assertThrows(
            RepoOpRejected.class, () -> this.dao.restore("libs", null, "admin")
        );
        MatcherAssert.assertThat(
            "the name is taken", rejected.status(), new IsEqual<>(409)
        );
        this.dao.restore("libs", "libs-old", "admin");
        MatcherAssert.assertThat(
            "restored under the other name with its original key",
            RepoLifecycleDaoTest.db.value("SELECT repo_key FROM repositories WHERE name = 'libs-old'"),
            new IsEqual<>("libs")
        );
    }

    @Test
    void restoreOfUnknownKeyIsRefused() {
        final RepoOpRejected rejected = Assertions.assertThrows(
            RepoOpRejected.class, () -> this.dao.restore("ghost", null, "admin")
        );
        MatcherAssert.assertThat(rejected.status(), new IsEqual<>(404));
    }

    @Test
    void newRepositoryCannotTakeANameThatStillRoutesElsewhere() {
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        this.dao.rename("libs", "releases", 7, "admin");
        final RepoOpRejected rejected = Assertions.assertThrows(
            RepoOpRejected.class, () -> this.dao.claimName("libs")
        );
        MatcherAssert.assertThat(rejected.status(), new IsEqual<>(409));
    }

    @Test
    void newRepositoryTakesANameThatOnlyTranslatedEvents() {
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        this.dao.rename("libs", "releases", 0, "admin");
        this.dao.claimName("libs");
        MatcherAssert.assertThat(
            RepoLifecycleDaoTest.db.value("SELECT alias FROM repository_aliases WHERE alias = 'libs'"),
            new IsNull<>()
        );
    }

    @Test
    void freeKeyAvoidsKeysInUseAndLeftoverDirectories() {
        RepoLifecycleDaoTest.db.repo("libs", "maven");
        MatcherAssert.assertThat(
            "a key held by a repository is skipped",
            this.dao.freeKey("libs", java.util.Set.of()), new IsEqual<>("libs~2")
        );
        MatcherAssert.assertThat(
            "a leftover directory is skipped too",
            this.dao.freeKey("fresh", java.util.Set.of("fresh", "fresh~2")), new IsEqual<>("fresh~3")
        );
    }
}
