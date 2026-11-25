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
import javax.json.Json;
import org.hamcrest.MatcherAssert;
import org.hamcrest.collection.IsEmptyCollection;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests for {@link RepoJobRunner}: the purge of a deleted repository is
 * paged, resumable, idempotent and safe against a node dying, a storage that
 * fails or throttles, and writers that race it.
 *
 * <p>Nothing here asserts a duration. Paging is proven by the sizes of the
 * delete batches and the positions the listing resumed from; waiting is
 * proven by the job's state.</p>
 *
 * @since 2.2.14
 */
@Testcontainers
@Timeout(120)
@SuppressWarnings("PMD.TooManyMethods")
final class RepoJobRunnerTest {

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
     * Storage the runner purges.
     */
    private FakePurgeStore store;

    /**
     * Lifecycle operations.
     */
    private RepoLifecycleDao life;

    /**
     * Jobs.
     */
    private RepoJobDao jobs;

    @BeforeAll
    static void migrate() {
        RepoJobRunnerTest.db = new RepoJobsDb(RepoJobRunnerTest.PG);
    }

    @AfterAll
    static void close() {
        RepoJobRunnerTest.db.close();
    }

    @BeforeEach
    void init() {
        RepoJobRunnerTest.db.clean();
        this.store = new FakePurgeStore();
        this.life = new RepoLifecycleDao(RepoJobRunnerTest.db.source());
        this.jobs = new RepoJobDao(RepoJobRunnerTest.db.source());
    }

    @Test
    void purgesEveryObjectInPagesAndFreesTheKey() {
        RepoJobRunnerTest.db.repo("libs", "maven");
        RepoJobRunnerTest.db.repo("libs-proxy", "maven-proxy");
        this.store.fill("libs", 2500).fill("libs-proxy", 7);
        RepoJobRunnerTest.db.artifact("libs", "a", "1");
        RepoJobRunnerTest.db.artifact("libs", "a", "2");
        RepoJobRunnerTest.db.artifact("libs-proxy", "b", "1");
        final long job = this.life.delete("libs", false, Duration.ZERO, "admin", null).job();
        try (RepoJobRunner runner = this.runner("node-a", Duration.ZERO)) {
            runner.drain();
        }
        MatcherAssert.assertThat(
            "the directory is deleted in batches of at most 1000",
            this.store.deleteBatches(), new IsEqual<>(List.of(1000, 1000, 500))
        );
        MatcherAssert.assertThat(
            "the sibling repository whose name starts the same is untouched",
            this.store.left().size(), new IsEqual<>(7)
        );
        MatcherAssert.assertThat(
            "unfinished uploads are aborted", this.store.abortCalls(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "only the deleted repository's index rows are removed",
            RepoJobRunnerTest.db.column("SELECT repo_name FROM artifacts"),
            new IsEqual<>(List.of("libs-proxy"))
        );
        final RepoJob done = this.jobs.get(job).orElseThrow();
        MatcherAssert.assertThat(
            "the job is done", done.state(), new IsEqual<>(RepoJob.DONE)
        );
        MatcherAssert.assertThat(
            "it counted the objects", done.objects(), new IsEqual<>(2500L)
        );
        MatcherAssert.assertThat(
            "it counted the rows", done.rows(), new IsEqual<>(2L)
        );
        MatcherAssert.assertThat(
            "the tombstone is gone", this.life.tombstones(), new IsEmptyCollection<>()
        );
        RepoJobRunnerTest.db.repo("libs", "maven");
        MatcherAssert.assertThat(
            "the key is free again for a repository of that name",
            RepoJobRunnerTest.db.value("SELECT repo_key FROM repositories WHERE name = 'libs'"),
            new IsEqual<>("libs")
        );
    }

    @Test
    void doesNothingInsideTheRestoreWindow() {
        RepoJobRunnerTest.db.repo("libs", "maven");
        this.store.fill("libs", 10);
        this.life.delete("libs", false, Duration.ofHours(24), "admin", null);
        final int worked;
        try (RepoJobRunner runner = this.runner("node-a", Duration.ZERO)) {
            worked = runner.drain();
        }
        MatcherAssert.assertThat(
            "no job is due", worked, new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "nothing is deleted", this.store.left().size(), new IsEqual<>(10)
        );
    }

    @Test
    void restoredRepositoryIsNeverPurged() {
        RepoJobRunnerTest.db.repo("libs", "maven");
        this.store.fill("libs", 10);
        final long job = this.life.delete("libs", false, Duration.ofHours(1), "admin", null).job();
        this.life.restore("libs", null, "admin");
        this.jobs.startNow(job);
        try (RepoJobRunner runner = this.runner("node-a", Duration.ZERO)) {
            runner.drain();
        }
        MatcherAssert.assertThat(this.store.left().size(), new IsEqual<>(10));
    }

    @Test
    void resumesAfterTheLastDeletedPageWhenAnAttemptFails() {
        RepoJobRunnerTest.db.repo("libs", "maven");
        this.store.fill("libs", 2500);
        final long job = this.life.delete("libs", false, Duration.ZERO, "admin", null).job();
        this.store.failDeleteAfter(1);
        try (RepoJobRunner runner = this.runner("node-a", Duration.ofHours(1))) {
            runner.drain();
            final RepoJob failed = this.jobs.get(job).orElseThrow();
            MatcherAssert.assertThat(
                "the failed attempt is recorded and the job goes back to pending",
                List.of(failed.state(), String.valueOf(failed.attempts()), failed.error()),
                new IsEqual<>(List.of(RepoJob.PENDING, "1", "storage unavailable"))
            );
            MatcherAssert.assertThat(
                "the checkpoint is the last name of the page that was deleted",
                failed.checkpoint(), new IsEqual<>("libs/pkg/000999.bin")
            );
            MatcherAssert.assertThat(
                "the first page is gone, the rest is not",
                this.store.left().size(), new IsEqual<>(1500)
            );
            this.store.listedFrom().clear();
            MatcherAssert.assertThat(
                "the retry waits for its backoff", runner.drain(), new IsEqual<>(0)
            );
            this.due(job);
            runner.drain();
        }
        MatcherAssert.assertThat(
            "the retry lists from the checkpoint, not from the beginning",
            this.store.listedFrom().get(0), new IsEqual<>("libs/pkg/000999.bin")
        );
        MatcherAssert.assertThat(
            "everything is deleted", this.store.left(), new IsEmptyCollection<>()
        );
        MatcherAssert.assertThat(
            "the job is done with the objects of both attempts counted",
            this.jobs.get(job).orElseThrow().objects(), new IsEqual<>(2500L)
        );
    }

    @Test
    void anotherNodeFinishesTheJobOfANodeThatDied() {
        RepoJobRunnerTest.db.repo("libs", "maven");
        this.store.fill("libs", 2500);
        final long job = this.life.delete("libs", false, Duration.ZERO, "admin", null).job();
        // node-a deletes one page and then dies holding the lease.
        this.jobs.claim("node-a", Duration.ofMinutes(10));
        final PurgeStore.Page page = this.store.list("libs", java.util.Optional.empty(), 1000);
        this.store.delete(page.names());
        this.jobs.progress(
            job, "node-a", Duration.ofMinutes(10), RepoJob.PHASE_OBJECTS,
            page.next().orElseThrow(), 1000, 0
        );
        this.store.listedFrom().clear();
        try (RepoJobRunner other = this.runner("node-b", Duration.ZERO)) {
            MatcherAssert.assertThat(
                "while the lease is live the other node leaves the job alone",
                other.drain(), new IsEqual<>(0)
            );
            RepoJobRunnerTest.db.exec(
                "UPDATE repo_jobs SET lease_until = NOW() - INTERVAL '1 second' WHERE id = " + job
            );
            other.drain();
        }
        MatcherAssert.assertThat(
            "the other node resumed at the dead node's checkpoint",
            this.store.listedFrom().get(0), new IsEqual<>("libs/pkg/000999.bin")
        );
        MatcherAssert.assertThat(
            "and finished the purge", this.store.left(), new IsEmptyCollection<>()
        );
        MatcherAssert.assertThat(
            "the job is done", this.jobs.get(job).orElseThrow().state(), new IsEqual<>(RepoJob.DONE)
        );
    }

    @Test
    @Timeout(60)
    void closingTheRunnerGivesBackTheJobInProgress() throws Exception {
        RepoJobRunnerTest.db.repo("libs", "maven");
        this.store.fill("libs", 2500);
        final long job = this.life.delete("libs", false, Duration.ZERO, "admin", null).job();
        final java.util.concurrent.CountDownLatch working =
            new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch stopped =
            new java.util.concurrent.CountDownLatch(1);
        // The job is in the middle of its work when the node shuts down.
        this.store.afterDelete(
            ignored -> {
                working.countDown();
                try {
                    stopped.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        );
        final RepoJobRunner runner = this.runner("node-a", Duration.ZERO);
        final Thread node = new Thread(runner::drain, "node-a-job");
        node.start();
        MatcherAssert.assertThat(
            "the job is being worked on",
            working.await(30, java.util.concurrent.TimeUnit.SECONDS), new IsEqual<>(true)
        );
        runner.close();
        final RepoJob freed = this.jobs.get(job).orElseThrow();
        stopped.countDown();
        node.join(30_000L);
        MatcherAssert.assertThat(
            "the job waits for another node, with no owner and no attempt counted",
            List.of(
                freed.state(), String.valueOf(freed.owner()), String.valueOf(freed.attempts())
            ),
            new IsEqual<>(List.of(RepoJob.PENDING, "null", "0"))
        );
        try (RepoJobRunner other = this.runner("node-b", Duration.ZERO)) {
            other.drain();
        }
        MatcherAssert.assertThat(
            "and another node finishes it from the checkpoint",
            this.store.left(), new IsEmptyCollection<>()
        );
    }

    @Test
    void stopsWhenItsLeaseIsTaken() {
        RepoJobRunnerTest.db.repo("libs", "maven");
        this.store.fill("libs", 2500);
        final long job = this.life.delete("libs", false, Duration.ZERO, "admin", null).job();
        this.store.afterDelete(
            ignored -> RepoJobRunnerTest.db.exec(
                "UPDATE repo_jobs SET lease_owner = 'node-b' WHERE id = " + job
            )
        );
        try (RepoJobRunner runner = this.runner("node-a", Duration.ZERO)) {
            runner.drain();
        }
        MatcherAssert.assertThat(
            "the node stopped after the batch during which it lost the job",
            this.store.deleteBatches(), new IsEqual<>(List.of(1000))
        );
        final RepoJob held = this.jobs.get(job).orElseThrow();
        MatcherAssert.assertThat(
            "the job belongs to the other node and was not marked done or failed",
            List.of(held.state(), held.owner(), String.valueOf(held.attempts())),
            new IsEqual<>(List.of(RepoJob.RUNNING, "node-b", "0"))
        );
    }

    @Test
    void backsOffAndContinuesWhenTheStorageThrottles() {
        RepoJobRunnerTest.db.repo("libs", "maven");
        this.store.fill("libs", 1200);
        final long job = this.life.delete("libs", false, Duration.ZERO, "admin", null).job();
        this.store.throttle(4);
        try (RepoJobRunner runner = this.runner("node-a", Duration.ZERO)) {
            runner.drain();
        }
        final RepoJob done = this.jobs.get(job).orElseThrow();
        MatcherAssert.assertThat(
            "the purge finished", this.store.left(), new IsEmptyCollection<>()
        );
        MatcherAssert.assertThat(
            "throttling is not a failed attempt",
            List.of(done.state(), String.valueOf(done.attempts())),
            new IsEqual<>(List.of(RepoJob.DONE, "0"))
        );
    }

    @Test
    void releasesTheJobWhenThrottlingDoesNotStop() {
        RepoJobRunnerTest.db.repo("libs", "maven");
        this.store.fill("libs", 1200);
        final long job = this.life.delete("libs", false, Duration.ZERO, "admin", null).job();
        this.store.throttle(10_000);
        try (RepoJobRunner runner = this.runner("node-a", Duration.ZERO, Duration.ZERO, Duration.ofHours(1))) {
            runner.drain();
            final RepoJob paused = this.jobs.get(job).orElseThrow();
            MatcherAssert.assertThat(
                "the job waits, uncounted, with the reason recorded",
                List.of(paused.state(), String.valueOf(paused.attempts())),
                new IsEqual<>(List.of(RepoJob.PENDING, "0"))
            );
            MatcherAssert.assertThat(
                "the reason names throttling", paused.error(), new StringContains("throttled")
            );
            this.store.throttle(0);
            this.due(job);
            runner.drain();
        }
        MatcherAssert.assertThat(
            "it finishes once the storage recovers", this.store.left(), new IsEmptyCollection<>()
        );
    }

    @Test
    void objectWrittenBehindTheCursorIsCaughtByTheVerifyPass() {
        RepoJobRunnerTest.db.repo("libs", "maven");
        this.store.fill("libs", 1500);
        final long job = this.life.delete("libs", false, Duration.ZERO, "admin", null).job();
        final boolean[] written = {false};
        this.store.afterDelete(
            fake -> {
                if (!written[0]) {
                    written[0] = true;
                    // An upload that was in flight at the delete lands now,
                    // at a name the listing has already passed.
                    fake.put("libs/a-late-upload.bin");
                }
            }
        );
        try (RepoJobRunner runner = this.runner("node-a", Duration.ZERO)) {
            runner.drain();
        }
        MatcherAssert.assertThat(
            "the late object is deleted too", this.store.left(), new IsEmptyCollection<>()
        );
        MatcherAssert.assertThat(
            "by a second pass", this.store.deleteBatches(), new IsEqual<>(List.of(1000, 500, 1))
        );
        MatcherAssert.assertThat(
            "the job is done", this.jobs.get(job).orElseThrow().state(), new IsEqual<>(RepoJob.DONE)
        );
    }

    @Test
    void failsTheAttemptWhenSomethingKeepsWriting() {
        RepoJobRunnerTest.db.repo("libs", "maven");
        this.store.fill("libs", 3);
        final long job = this.life.delete("libs", false, Duration.ZERO, "admin", null).job();
        final int[] seq = {0};
        this.store.afterDelete(
            fake -> {
                seq[0] += 1;
                fake.put(String.format("libs/a-writer-%04d.bin", seq[0]));
            }
        );
        try (RepoJobRunner runner = this.runner("node-a", Duration.ofHours(1), Duration.ZERO)) {
            runner.drain();
        }
        final RepoJob failed = this.jobs.get(job).orElseThrow();
        MatcherAssert.assertThat(
            "the attempt failed and will be retried",
            List.of(failed.state(), String.valueOf(failed.attempts())),
            new IsEqual<>(List.of(RepoJob.PENDING, "1"))
        );
        MatcherAssert.assertThat(
            "the error says what is wrong",
            failed.error(), new StringContains("something still writes there")
        );
        MatcherAssert.assertThat(
            "the repository's rows and tombstone are kept until the directory is empty",
            this.life.tombstone("libs").isPresent(), new IsEqual<>(true)
        );
    }

    @Test
    void waitsForInFlightRequestsBeforeTheLastCheck() {
        RepoJobRunnerTest.db.repo("libs", "maven");
        this.store.fill("libs", 5);
        RepoJobRunnerTest.db.artifact("libs", "a", "1");
        final long job = this.life.delete("libs", false, Duration.ZERO, "admin", null).job();
        try (RepoJobRunner runner = this.runner("node-a", Duration.ZERO, Duration.ofHours(1))) {
            runner.drain();
            final RepoJob waiting = this.jobs.get(job).orElseThrow();
            MatcherAssert.assertThat(
                "the objects are deleted at once", this.store.left(), new IsEmptyCollection<>()
            );
            MatcherAssert.assertThat(
                "the job waits in the verify phase, not counted as a failure",
                List.of(waiting.state(), waiting.phase(), String.valueOf(waiting.attempts())),
                new IsEqual<>(List.of(RepoJob.PENDING, RepoJob.PHASE_VERIFY, "0"))
            );
            MatcherAssert.assertThat(
                "rows and tombstone are kept until the check",
                RepoJobRunnerTest.db.count("SELECT COUNT(*) FROM artifacts"), new IsEqual<>(1L)
            );
            MatcherAssert.assertThat(
                "it is not due again before the wait is over", runner.drain(), new IsEqual<>(0)
            );
            // The wait is over: the repository has been gone long enough.
            RepoJobRunnerTest.db.exec(
                "UPDATE repository_tombstones SET deleted_at = NOW() - INTERVAL '2 hours'"
            );
            RepoJobRunnerTest.db.exec("UPDATE repo_jobs SET not_before = NOW() WHERE id = " + job);
            runner.drain();
        }
        MatcherAssert.assertThat(
            "then the rows go and the job ends",
            List.of(
                this.jobs.get(job).orElseThrow().state(),
                String.valueOf(RepoJobRunnerTest.db.count("SELECT COUNT(*) FROM artifacts"))
            ),
            new IsEqual<>(List.of(RepoJob.DONE, "0"))
        );
    }

    @Test
    void parksTheJobAfterRepeatedFailuresAndAnAdminRetryFinishesIt() {
        RepoJobRunnerTest.db.repo("libs", "maven");
        this.store.fill("libs", 10);
        final long job = this.life.delete("libs", false, Duration.ZERO, "admin", null).job();
        try (RepoJobRunner runner = this.runner("node-a", Duration.ofHours(1))) {
            for (int attempt = 0; attempt < RepoJobDao.MAX_ATTEMPTS; attempt += 1) {
                this.store.failDeleteAfter(0);
                this.due(job);
                runner.drain();
            }
            MatcherAssert.assertThat(
                "the job is parked as failed",
                this.jobs.get(job).orElseThrow().state(), new IsEqual<>(RepoJob.FAILED)
            );
            MatcherAssert.assertThat(
                "a parked job is not picked up", runner.drain(), new IsEqual<>(0)
            );
            this.jobs.retry(job);
            runner.drain();
        }
        MatcherAssert.assertThat(
            "the retry finishes it",
            List.of(this.jobs.get(job).orElseThrow().state(), String.valueOf(this.store.left().size())),
            new IsEqual<>(List.of(RepoJob.DONE, "0"))
        );
    }

    @Test
    void purgeOfAGroupTouchesNoStorage() {
        RepoJobRunnerTest.db.group("all");
        this.store.fill("all", 3);
        final long job = this.life.delete("all", false, Duration.ZERO, "admin", null).job();
        try (RepoJobRunner runner = this.runner("node-a", Duration.ZERO)) {
            runner.drain();
        }
        MatcherAssert.assertThat(
            "a group has no directory of its own to purge",
            this.store.left().size(), new IsEqual<>(3)
        );
        MatcherAssert.assertThat(
            "the job is done", this.jobs.get(job).orElseThrow().state(), new IsEqual<>(RepoJob.DONE)
        );
    }

    @Test
    void purgeRemovesOnlyTheHistoryWrittenBeforeTheDelete() {
        RepoJobRunnerTest.db.repo("libs", "maven");
        this.history("libs", "old", 1L);
        this.life.delete("libs", false, Duration.ZERO, "admin", null);
        // A new repository takes the name and writes history of its own.
        this.history("libs", "new", System.currentTimeMillis() + 3_600_000L);
        try (RepoJobRunner runner = this.runner("node-a", Duration.ZERO)) {
            runner.drain();
        }
        MatcherAssert.assertThat(
            RepoJobRunnerTest.db.column("SELECT artifact FROM artifact_cooldowns_history"),
            new IsEqual<>(List.of("new"))
        );
    }

    @Test
    void renameFollowUpMovesTheCooldownHistory() {
        RepoJobRunnerTest.db.repo("libs", "maven");
        this.history("libs", "a", 1L);
        this.history("other", "b", 1L);
        final long job = this.life.rename("libs", "releases", 0, "admin").job();
        try (RepoJobRunner runner = this.runner("node-a", Duration.ZERO)) {
            runner.drain();
        }
        MatcherAssert.assertThat(
            "the renamed repository's history follows, other history is untouched",
            RepoJobRunnerTest.db.column(
                "SELECT repo_name FROM artifact_cooldowns_history ORDER BY artifact"
            ),
            new IsEqual<>(List.of("releases", "other"))
        );
        MatcherAssert.assertThat(
            "the job is done", this.jobs.get(job).orElseThrow().state(), new IsEqual<>(RepoJob.DONE)
        );
    }

    @Test
    void orphanPurgeDeletesADirectoryNoRepositoryOwns() {
        this.store.fill("leftover", 1100).fill("kept", 2);
        final long job = this.jobs.enqueue(
            RepoJob.Type.ORPHAN_PURGE, "leftover", "leftover",
            Json.createObjectBuilder()
                .add("storage", Json.createObjectBuilder().add("type", "fs")).build(),
            "admin", null
        );
        try (RepoJobRunner runner = this.runner("node-a", Duration.ZERO)) {
            runner.drain();
        }
        MatcherAssert.assertThat(
            "only the orphaned directory is emptied", this.store.left().size(), new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "the job is done", this.jobs.get(job).orElseThrow().state(), new IsEqual<>(RepoJob.DONE)
        );
    }

    @Test
    void repeatedPurgeOfAnEmptyDirectoryIsHarmless() {
        RepoJobRunnerTest.db.repo("libs", "maven");
        final long job = this.life.delete("libs", false, Duration.ZERO, "admin", null).job();
        try (RepoJobRunner runner = this.runner("node-a", Duration.ZERO)) {
            runner.drain();
        }
        MatcherAssert.assertThat(
            "no delete call is made for nothing",
            this.store.deleteBatches(), new IsEmptyCollection<>()
        );
        MatcherAssert.assertThat(
            "the job is done", this.jobs.get(job).orElseThrow().state(), new IsEqual<>(RepoJob.DONE)
        );
    }

    /**
     * Runner with no settle wait.
     * @param node Node id
     * @param retry Delay after a failed attempt
     * @return Runner
     */
    private RepoJobRunner runner(final String node, final Duration retry) {
        return this.runner(node, retry, Duration.ZERO);
    }

    /**
     * Runner.
     * @param node Node id
     * @param retry Delay after a failed attempt
     * @param settle Time a repository must be gone before the last check
     * @return Runner
     */
    private RepoJobRunner runner(final String node, final Duration retry, final Duration settle) {
        return this.runner(node, retry, settle, Duration.ZERO);
    }

    /**
     * Runner.
     * @param node Node id
     * @param retry Delay after a failed attempt
     * @param settle Time a repository must be gone before the last check
     * @param throttled Delay before a job released for throttling is retried
     * @return Runner
     */
    private RepoJobRunner runner(
        final String node, final Duration retry, final Duration settle, final Duration throttled
    ) {
        return new RepoJobRunner(
            RepoJobRunnerTest.db.source(), config -> this.store, node,
            new RepoJobRunner.Timing(
                Duration.ofHours(1), Duration.ofMinutes(10), settle, retry,
                Duration.ofMillis(1), throttled
            )
        );
    }

    /**
     * End a job's backoff: the next drain picks it up.
     * @param job Job id
     */
    private void due(final long job) {
        RepoJobRunnerTest.db.exec("UPDATE repo_jobs SET not_before = NOW() WHERE id = " + job);
    }

    /**
     * Add a cooldown history row.
     * @param repo Repository name
     * @param artifact Artifact
     * @param archived Archive time, epoch milliseconds
     */
    private void history(final String repo, final String artifact, final long archived) {
        RepoJobRunnerTest.db.exec(
            "INSERT INTO artifact_cooldowns_history (original_id, repo_type, repo_name, artifact,"
                + " version, reason, blocked_by, blocked_at, blocked_until, archived_at,"
                + " archive_reason, archived_by) VALUES (1, 'maven', ?, ?, '1', 'FRESH_RELEASE',"
                + " 'system', 1, 2, " + archived + ", 'EXPIRED', 'system')",
            repo, artifact
        );
    }
}
