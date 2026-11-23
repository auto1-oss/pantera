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
import java.util.Optional;
import javax.json.JsonValue;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests for {@link RepoJobDao}: one node works on a job at a time, a dead
 * node's job is taken over, and a node that lost its lease cannot write.
 *
 * @since 2.2.14
 */
@Testcontainers
@SuppressWarnings("PMD.TooManyMethods")
final class RepoJobDaoTest {

    /**
     * PostgreSQL.
     */
    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();

    /**
     * Lease long enough to never expire inside a test.
     */
    private static final Duration LONG = Duration.ofMinutes(10);

    /**
     * Database helper.
     */
    private static RepoJobsDb db;

    /**
     * Subject.
     */
    private RepoJobDao jobs;

    @BeforeAll
    static void migrate() {
        RepoJobDaoTest.db = new RepoJobsDb(RepoJobDaoTest.PG);
    }

    @AfterAll
    static void close() {
        RepoJobDaoTest.db.close();
    }

    @BeforeEach
    void init() {
        RepoJobDaoTest.db.clean();
        this.jobs = new RepoJobDao(RepoJobDaoTest.db.source());
    }

    @Test
    void claimedJobIsNotGivenToASecondNode() {
        final long id = this.purge("libs");
        final Optional<RepoJob> first = this.jobs.claim("node-a", RepoJobDaoTest.LONG);
        final Optional<RepoJob> second = this.jobs.claim("node-b", RepoJobDaoTest.LONG);
        MatcherAssert.assertThat(
            "the first node gets the job", first.map(RepoJob::id), new IsEqual<>(Optional.of(id))
        );
        MatcherAssert.assertThat(
            "the second node gets nothing", second.isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void claimMovesAWaitingPurgeToItsObjectPhase() {
        this.purge("libs");
        final RepoJob job = this.jobs.claim("node-a", RepoJobDaoTest.LONG).orElseThrow();
        MatcherAssert.assertThat(
            "running", job.state(), new IsEqual<>(RepoJob.RUNNING)
        );
        MatcherAssert.assertThat(
            "in the object phase", job.phase(), new IsEqual<>(RepoJob.PHASE_OBJECTS)
        );
    }

    @Test
    void claimMovesARenameFollowUpToItsRowPhase() {
        this.jobs.enqueue(
            RepoJob.Type.RENAME_FOLLOWUP, "libs", "releases", JsonValue.EMPTY_JSON_OBJECT, "admin", null
        );
        MatcherAssert.assertThat(
            this.jobs.claim("node-a", RepoJobDaoTest.LONG).orElseThrow().phase(),
            new IsEqual<>(RepoJob.PHASE_ROWS)
        );
    }

    @Test
    void jobIsNotClaimedBeforeItsStartTime() {
        final long id = this.purge("libs");
        RepoJobDaoTest.db.exec(
            "UPDATE repo_jobs SET not_before = NOW() + INTERVAL '1 hour' WHERE id = " + id
        );
        MatcherAssert.assertThat(
            "not due yet",
            this.jobs.claim("node-a", RepoJobDaoTest.LONG).isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "ending the restore window makes it due", this.jobs.startNow(id), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "now it is claimed",
            this.jobs.claim("node-a", RepoJobDaoTest.LONG).isPresent(), new IsEqual<>(true)
        );
    }

    @Test
    void jobOfADeadNodeIsTakenOverAndResumesFromItsCheckpoint() {
        final long id = this.purge("libs");
        this.jobs.claim("node-a", RepoJobDaoTest.LONG);
        this.jobs.progress(
            id, "node-a", RepoJobDaoTest.LONG, RepoJob.PHASE_OBJECTS, "libs/m/900.jar", 900, 0
        );
        // node-a dies: its lease runs out.
        RepoJobDaoTest.db.exec(
            "UPDATE repo_jobs SET lease_until = NOW() - INTERVAL '1 second' WHERE id = " + id
        );
        final RepoJob taken = this.jobs.claim("node-b", RepoJobDaoTest.LONG).orElseThrow();
        MatcherAssert.assertThat(
            "the second node holds it now", taken.owner(), new IsEqual<>("node-b")
        );
        MatcherAssert.assertThat(
            "it resumes after the last name the dead node recorded",
            taken.checkpoint(), new IsEqual<>("libs/m/900.jar")
        );
        MatcherAssert.assertThat(
            "the progress so far is kept", taken.objects(), new IsEqual<>(900L)
        );
        MatcherAssert.assertThat(
            "the phase is kept", taken.phase(), new IsEqual<>(RepoJob.PHASE_OBJECTS)
        );
    }

    @Test
    void nodeThatLostItsLeaseCannotWriteProgressOrFinish() {
        final long id = this.purge("libs");
        this.jobs.claim("node-a", RepoJobDaoTest.LONG);
        RepoJobDaoTest.db.exec(
            "UPDATE repo_jobs SET lease_until = NOW() - INTERVAL '1 second' WHERE id = " + id
        );
        this.jobs.claim("node-b", RepoJobDaoTest.LONG);
        MatcherAssert.assertThat(
            "progress of the old holder is refused",
            this.jobs.progress(id, "node-a", RepoJobDaoTest.LONG, RepoJob.PHASE_ROWS, null, 5, 5),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "renewal of the old holder is refused",
            this.jobs.renew(id, "node-a", RepoJobDaoTest.LONG), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the old holder cannot finish the job",
            this.jobs.done(id, "node-a"), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the job is untouched by the old holder",
            this.jobs.get(id).orElseThrow().phase(), new IsEqual<>(RepoJob.PHASE_OBJECTS)
        );
    }

    @Test
    void failedAttemptIsRetriedLaterAndParkedAfterTheLimit() {
        final long id = this.purge("libs");
        for (int attempt = 1; attempt < RepoJobDao.MAX_ATTEMPTS; attempt += 1) {
            this.jobs.claim("node-a", RepoJobDaoTest.LONG);
            MatcherAssert.assertThat(
                "attempt " + attempt + " goes back to pending",
                this.jobs.release(id, "node-a", "boom", Duration.ZERO, true),
                new IsEqual<>(Optional.of(RepoJob.PENDING))
            );
        }
        this.jobs.claim("node-a", RepoJobDaoTest.LONG);
        MatcherAssert.assertThat(
            "the last attempt parks the job",
            this.jobs.release(id, "node-a", "boom", Duration.ZERO, true),
            new IsEqual<>(Optional.of(RepoJob.FAILED))
        );
        MatcherAssert.assertThat(
            "a parked job is not claimed",
            this.jobs.claim("node-a", RepoJobDaoTest.LONG).isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "an admin retry queues it again", this.jobs.retry(id), new IsEqual<>(true)
        );
        final RepoJob again = this.jobs.claim("node-a", RepoJobDaoTest.LONG).orElseThrow();
        MatcherAssert.assertThat(
            "with a fresh attempt count", again.attempts(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "and the last error kept for the operator", again.error(), new IsEqual<>("boom")
        );
    }

    @Test
    void throttledReleaseDoesNotCountAsAnAttempt() {
        final long id = this.purge("libs");
        this.jobs.claim("node-a", RepoJobDaoTest.LONG);
        this.jobs.release(id, "node-a", "slow down", Duration.ZERO, false);
        MatcherAssert.assertThat(
            this.jobs.get(id).orElseThrow().attempts(), new IsEqual<>(0)
        );
    }

    @Test
    void releaseDelaysTheNextAttempt() {
        final long id = this.purge("libs");
        this.jobs.claim("node-a", RepoJobDaoTest.LONG);
        this.jobs.release(id, "node-a", "boom", Duration.ofHours(1), true);
        MatcherAssert.assertThat(
            this.jobs.claim("node-a", RepoJobDaoTest.LONG).isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void secondLivePurgeOfTheSameKeyIsRefused() {
        this.purge("libs");
        final RepoOpRejected rejected = Assertions.assertThrows(
            RepoOpRejected.class, () -> this.purge("libs")
        );
        MatcherAssert.assertThat(rejected.status(), new IsEqual<>(409));
    }

    @Test
    void finishedJobsFreeTheKeyForANewPurge() {
        final long id = this.purge("libs");
        this.jobs.claim("node-a", RepoJobDaoTest.LONG);
        this.jobs.done(id, "node-a");
        MatcherAssert.assertThat(
            "a new purge of the key can be queued", this.purge("libs") > id, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "finished jobs are counted by state", this.jobs.count(RepoJob.DONE), new IsEqual<>(1L)
        );
    }

    @Test
    void retryAndStartOnlyApplyToTheStateTheyAreFor() {
        final long id = this.purge("libs");
        MatcherAssert.assertThat(
            "a pending job is not retried", this.jobs.retry(id), new IsEqual<>(false)
        );
        this.jobs.claim("node-a", RepoJobDaoTest.LONG);
        MatcherAssert.assertThat(
            "a started job cannot be started again", this.jobs.startNow(id), new IsEqual<>(false)
        );
    }

    @Test
    void oldFinishedJobsArePruned() {
        final long id = this.purge("libs");
        this.jobs.claim("node-a", RepoJobDaoTest.LONG);
        this.jobs.done(id, "node-a");
        RepoJobDaoTest.db.exec(
            "UPDATE repo_jobs SET finished_at = NOW() - INTERVAL '40 days' WHERE id = " + id
        );
        MatcherAssert.assertThat(this.jobs.prune(Duration.ofDays(30)), new IsEqual<>(1));
    }

    /**
     * Queue an orphan purge of a directory (needs no tombstone).
     * @param key Directory
     * @return Job id
     */
    private long purge(final String key) {
        return this.jobs.enqueue(
            RepoJob.Type.ORPHAN_PURGE, key, key, JsonValue.EMPTY_JSON_OBJECT, "admin", null
        );
    }
}
