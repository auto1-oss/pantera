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

import com.auto1.pantera.cache.StoragesCache;
import com.auto1.pantera.db.DbConsumer;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.auto1.pantera.index.DbArtifactIndex;
import com.auto1.pantera.index.SearchResult;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.settings.repo.DbRepositories;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.settings.repo.RepoKeys;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A renamed repository is addressed by its new name everywhere while its
 * data and index rows stay under its key; a deleted repository's rows are
 * invisible and are not written again.
 *
 * @since 2.2.14
 */
@Testcontainers
@SuppressWarnings("PMD.TooManyMethods")
final class RepoKeysTranslationTest {

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
     * Lifecycle operations.
     */
    private RepoLifecycleDao life;

    /**
     * Repositories as the server loads them.
     */
    private DbRepositories repos;

    @BeforeAll
    static void migrate() {
        RepoKeysTranslationTest.db = new RepoJobsDb(RepoKeysTranslationTest.PG);
    }

    @AfterAll
    static void close() {
        RepoKeysTranslationTest.db.close();
    }

    @BeforeEach
    void init() {
        RepoKeysTranslationTest.db.clean();
        this.life = new RepoLifecycleDao(RepoKeysTranslationTest.db.source());
        RepoKeysTranslationTest.db.repo("libs", "maven");
        RepoKeysTranslationTest.db.repo("other", "maven");
        this.repos = new DbRepositories(
            RepoKeysTranslationTest.db.source(), new StoragesCache(), false
        );
        RepoKeys.install(this.repos);
    }

    @AfterEach
    void reset() {
        RepoKeys.install(null);
    }

    @Test
    void renamedRepositoryKeepsItsStorageDirectory() {
        this.life.rename("libs", "releases", 0, "admin");
        this.repos.refreshAsync().join();
        final RepoConfig cfg = this.repos.config("releases").orElseThrow();
        MatcherAssert.assertThat(
            "the config carries the new name", cfg.name(), new IsEqual<>("releases")
        );
        MatcherAssert.assertThat(
            "and the key it was created with", cfg.repoKey(), new IsEqual<>("libs")
        );
        MatcherAssert.assertThat(
            "its storage is still the directory named by the key",
            cfg.storage().identifier().contains("prefix=libs"), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the old name is not served", this.repos.config("libs").isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void formerNameKeepsServingWhenTheRenameAskedForIt() {
        this.life.rename("libs", "releases", 30, "admin");
        this.repos.refreshAsync().join();
        MatcherAssert.assertThat(
            "a request for the old name resolves to the renamed repository",
            this.repos.config("libs").map(RepoConfig::name), new IsEqual<>(Optional.of("releases"))
        );
        MatcherAssert.assertThat(
            "the alias is reported for cache invalidation",
            this.repos.routableAliases("libs"), new IsEqual<>(List.of("libs"))
        );
        MatcherAssert.assertThat(
            "the alias is not listed as a repository",
            this.repos.configs().stream().map(RepoConfig::name).sorted().toList(),
            new IsEqual<>(List.of("other", "releases"))
        );
    }

    @Test
    void namesAndKeysTranslateBothWays() {
        this.life.rename("libs", "releases", 0, "admin");
        this.repos.refreshAsync().join();
        MatcherAssert.assertThat(
            "new name to key", this.repos.key("releases"), new IsEqual<>(Optional.of("libs"))
        );
        MatcherAssert.assertThat(
            "the former name still translates, for events queued under it",
            this.repos.key("libs"), new IsEqual<>(Optional.of("libs"))
        );
        MatcherAssert.assertThat(
            "key to current name", this.repos.name("libs"), new IsEqual<>(Optional.of("releases"))
        );
        MatcherAssert.assertThat(
            "a name of no repository has no key",
            this.repos.key("ghost").isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the live keys are the keys of the existing repositories",
            Set.copyOf(this.repos.live().orElseThrow()), new IsEqual<>(Set.of("libs", "other"))
        );
    }

    @Test
    void reloadReportsWhatChangedAndWhatWentAway() {
        final List<String> seen = new CopyOnWriteArrayList<>();
        this.repos.onChange(
            (changed, removed) -> seen.add(
                String.format(
                    "changed=%s removed=%s",
                    changed.stream().sorted().toList(), removed.stream().sorted().toList()
                )
            )
        );
        this.repos.refreshAsync().join();
        this.life.rename("libs", "releases", 0, "admin");
        this.repos.refreshAsync().join();
        this.life.delete("other", false, Duration.ofHours(1), "admin", null);
        this.repos.refreshAsync().join();
        MatcherAssert.assertThat(
            "a reload without changes reports nothing; a rename and a delete are reported",
            seen,
            new IsEqual<>(
                List.of("changed=[releases] removed=[libs]", "changed=[] removed=[other]")
            )
        );
    }

    @Test
    void searchFindsTheRowsOfARenamedRepositoryUnderItsNewName() {
        RepoKeysTranslationTest.db.artifact("libs", "com/acme/app", "1.0");
        RepoKeysTranslationTest.db.artifact("other", "com/acme/app", "1.0");
        this.life.rename("libs", "releases", 0, "admin");
        this.repos.refreshAsync().join();
        try (DbArtifactIndex index = new DbArtifactIndex(RepoKeysTranslationTest.db.source())) {
            final SearchResult byName = index.search(
                "app", 10, 0, null, "releases", "name", true, null
            ).join();
            MatcherAssert.assertThat(
                "filtering by the new name returns the row stored under the key",
                byName.documents().stream().map(doc -> doc.repoName()).toList(),
                new IsEqual<>(List.of("releases"))
            );
            final SearchResult scoped = index.search(
                "app", 10, 0, null, null, "name", true, List.of("releases")
            ).join();
            MatcherAssert.assertThat(
                "a permission scope given in names is applied to keys",
                scoped.documents().stream().map(doc -> doc.repoName()).toList(),
                new IsEqual<>(List.of("releases"))
            );
            MatcherAssert.assertThat(
                "the group lookup answers with current names",
                index.locate("com/acme/app").join().stream().sorted().toList(),
                new IsEqual<>(List.of("other", "releases"))
            );
        }
    }

    @Test
    void rowsOfADeletedRepositoryAreInvisibleBeforeTheyArePurged() {
        RepoKeysTranslationTest.db.artifact("libs", "com/acme/app", "1.0");
        RepoKeysTranslationTest.db.artifact("other", "com/acme/app", "1.0");
        this.life.delete("libs", false, Duration.ofHours(24), "admin", null);
        this.repos.refreshAsync().join();
        try (DbArtifactIndex index = new DbArtifactIndex(RepoKeysTranslationTest.db.source())) {
            final SearchResult all = index.search("app", 10, 0).join();
            MatcherAssert.assertThat(
                "an unrestricted search does not return them",
                all.documents().stream().map(doc -> doc.repoName()).toList(),
                new IsEqual<>(List.of("other"))
            );
            MatcherAssert.assertThat(
                "the total does not count them", all.totalHits(), new IsEqual<>(1L)
            );
            MatcherAssert.assertThat(
                "the group lookup does not send requests to it",
                index.locate("com/acme/app").join(), new IsEqual<>(List.of("other"))
            );
        }
        MatcherAssert.assertThat(
            "the rows themselves wait for the purge job",
            RepoKeysTranslationTest.db.count("SELECT COUNT(*) FROM artifacts"), new IsEqual<>(2L)
        );
    }

    @Test
    void uploadEventsAreStoredUnderTheKeyWhateverNameTheyCarry() {
        this.life.rename("libs", "releases", 0, "admin");
        this.repos.refreshAsync().join();
        final DbConsumer consumer = new DbConsumer(RepoKeysTranslationTest.db.source());
        // The consumer subscribes asynchronously and does not keep what was
        // sent before; the events are idempotent upserts, so they are sent
        // until they show up.
        Awaitility.await().atMost(60, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).until(
            () -> {
                consumer.accept(RepoKeysTranslationTest.upload("releases", "new-name"));
                // Queued on some node just before the rename committed.
                consumer.accept(RepoKeysTranslationTest.upload("libs", "old-name"));
                return RepoKeysTranslationTest.db.count("SELECT COUNT(*) FROM artifacts") == 2L;
            }
        );
        MatcherAssert.assertThat(
            RepoKeysTranslationTest.db.column(
                "SELECT repo_name || ':' || name FROM artifacts ORDER BY name"
            ),
            new IsEqual<>(List.of("libs:new-name", "libs:old-name"))
        );
    }

    @Test
    void uploadEventOfADeletedRepositoryIsNotWritten() {
        this.life.delete("libs", false, Duration.ofHours(24), "admin", null);
        this.repos.refreshAsync().join();
        final DbConsumer consumer = new DbConsumer(RepoKeysTranslationTest.db.source());
        // Sent until the marker shows up (the consumer subscribes
        // asynchronously). The dropped event always precedes a marker, so
        // once a marker is written the dropped one would be too.
        Awaitility.await().atMost(60, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).until(
            () -> {
                consumer.accept(RepoKeysTranslationTest.upload("libs", "late"));
                consumer.accept(RepoKeysTranslationTest.upload("other", "marker"));
                return RepoKeysTranslationTest.db.count(
                    "SELECT COUNT(*) FROM artifacts WHERE name = 'marker'"
                ) == 1L;
            }
        );
        MatcherAssert.assertThat(
            RepoKeysTranslationTest.db.column("SELECT name FROM artifacts"),
            new IsEqual<>(List.of("marker"))
        );
    }

    @Test
    void withoutADatabaseOfRepositoriesEveryNameIsItsOwnKey() {
        RepoKeys.install(null);
        MatcherAssert.assertThat(
            "identity translation", RepoKeys.shared().key("anything"),
            new IsEqual<>(Optional.of("anything"))
        );
        MatcherAssert.assertThat(
            "no restriction to live keys", RepoKeys.shared().live().isPresent(), new IsEqual<>(false)
        );
    }

    /**
     * An upload event.
     * @param repo Repository name the event carries
     * @param artifact Artifact name
     * @return Event
     */
    private static ArtifactEvent upload(final String repo, final String artifact) {
        return new ArtifactEvent(
            "maven", repo, "tester", artifact, "1.0", 10L, System.currentTimeMillis()
        );
    }
}
