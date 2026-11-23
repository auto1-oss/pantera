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

import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.metrics.MicrometerMetrics;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.json.JsonObject;
import javax.sql.DataSource;

/**
 * Runs repository jobs on this node: one at a time, on a thread of its own.
 *
 * <p>Every node runs one. A job is claimed with a lease in the database, so
 * exactly one node works on it; the others skip it. The work is written to
 * be stopped anywhere and started again: the object purge deletes a page of
 * names and records the last name as checkpoint, a missing object is not an
 * error, and row deletes are plain batches. A node that crashes simply stops
 * renewing its lease, and the next node to poll continues from the
 * checkpoint.</p>
 *
 * <p>Purge of a deleted repository:</p>
 * <ol>
 *   <li>{@code objects} - page through the repository's directory and
 *   batch-delete; abort unfinished multipart uploads.</li>
 *   <li>{@code verify} - once the repository has been gone long enough for
 *   requests that were in flight to end, list again; anything found sends
 *   the job back to {@code objects}.</li>
 *   <li>{@code rows} - delete the repository's index rows and cooldown
 *   history in batches, then drop the tombstone, which frees the key.</li>
 * </ol>
 *
 * @since 2.2.14
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.GodClass"})
public final class RepoJobRunner implements AutoCloseable {

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.repojob";

    /**
     * Objects listed and deleted per batch (the S3 DeleteObjects maximum).
     */
    private static final int PAGE = 1000;

    /**
     * Database rows deleted or rewritten per statement.
     */
    private static final int ROWS = 5000;

    /**
     * Verify passes that may find new objects before the job is given back
     * as failed: something keeps writing into a deleted repository.
     */
    private static final int MAX_PASSES = 5;

    /**
     * Consecutive throttled calls after which the job is released for a
     * while instead of holding the thread.
     */
    private static final int MAX_THROTTLES = 10;

    /**
     * The runner of this process.
     */
    private static final java.util.concurrent.atomic.AtomicReference<RepoJobRunner> SHARED =
        new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Jobs.
     */
    private final RepoJobDao jobs;

    /**
     * Tombstones.
     */
    private final RepoLifecycleDao lifecycle;

    /**
     * Database, for the row phases.
     */
    private final DataSource source;

    /**
     * Storage of a storage config.
     */
    private final Function<JsonObject, PurgeStore> stores;

    /**
     * Timing.
     */
    private final Timing timing;

    /**
     * Identity of this node in leases.
     */
    private final String node;

    /**
     * Job thread.
     */
    private final ScheduledExecutorService worker;

    /**
     * Lease renewal thread.
     */
    private final ScheduledExecutorService keeper;

    /**
     * Ctor.
     * @param source Database
     * @param stores Storage of a storage config
     * @param node Identity of this node in leases
     * @param timing Timing
     */
    public RepoJobRunner(
        final DataSource source, final Function<JsonObject, PurgeStore> stores,
        final String node, final Timing timing
    ) {
        this.jobs = new RepoJobDao(source);
        this.lifecycle = new RepoLifecycleDao(source);
        this.source = source;
        this.stores = stores;
        this.node = node;
        this.timing = timing;
        this.worker = Executors.newSingleThreadScheduledExecutor(
            task -> RepoJobRunner.thread(task, "pantera-repo-jobs")
        );
        this.keeper = Executors.newSingleThreadScheduledExecutor(
            task -> RepoJobRunner.thread(task, "pantera-repo-jobs-lease")
        );
    }

    /**
     * The runner of this process, when one was started.
     * @return Runner, empty before boot wires it and in tests
     */
    public static Optional<RepoJobRunner> shared() {
        return Optional.ofNullable(RepoJobRunner.SHARED.get());
    }

    /**
     * Make this the runner of this process.
     * @return This runner
     */
    public RepoJobRunner share() {
        RepoJobRunner.SHARED.set(this);
        return this;
    }

    /**
     * Start polling for jobs.
     */
    public void start() {
        this.worker.scheduleWithFixedDelay(
            this::drainSafely, this.timing.poll().toMillis(), this.timing.poll().toMillis(),
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Look for work now instead of at the next poll (a job was just queued).
     */
    public void wake() {
        if (!this.worker.isShutdown()) {
            this.worker.execute(this::drainSafely);
        }
    }

    /**
     * Run every job that is due, on the calling thread. Used by the worker
     * and by tests.
     * @return Number of jobs worked on
     */
    public int drain() {
        int count = 0;
        Optional<RepoJob> job = this.jobs.claim(this.node, this.timing.lease());
        while (job.isPresent()) {
            this.work(job.get());
            count += 1;
            job = this.jobs.claim(this.node, this.timing.lease());
        }
        return count;
    }

    @Override
    public void close() {
        this.worker.shutdownNow();
        this.keeper.shutdownNow();
    }

    /**
     * Drain, never letting a failure end the schedule.
     */
    private void drainSafely() {
        try {
            this.drain();
            this.jobs.prune(Duration.ofDays(30));
        } catch (final RuntimeException ex) {
            EcsLogger.error(RepoJobRunner.LOGGER)
                .message("Repository job poll failed")
                .eventCategory("process")
                .eventAction("repo_job_poll")
                .eventOutcome("failure")
                .error(ex)
                .field("log.source", "application")
                .log();
        }
    }

    /**
     * Work on a claimed job until it ends, fails, or the lease is lost.
     * @param job Claimed job
     */
    private void work(final RepoJob job) {
        RepoJobRunner.log(job, "repo_job_start", "success",
            String.format(
                "Repository job %d (%s of '%s') taken by this node in phase %s",
                job.id(), job.type().db(), job.name(), job.phase()
            )
        ).log();
        final ScheduledFuture<?> renewal = this.keeper.scheduleWithFixedDelay(
            () -> this.jobs.renew(job.id(), this.node, this.timing.lease()),
            this.timing.lease().toMillis() / 3, this.timing.lease().toMillis() / 3,
            TimeUnit.MILLISECONDS
        );
        try {
            final Outcome outcome = switch (job.type()) {
                case PURGE -> this.purge(job);
                case ORPHAN_PURGE -> this.orphan(job);
                case RENAME_FOLLOWUP -> this.followup(job);
            };
            this.settle(job, outcome);
        } catch (final LeaseLost lost) {
            RepoJobRunner.log(job, "repo_job_lease_lost", "unknown",
                String.format(
                    "Repository job %d is no longer held by this node (taken over, restored or cancelled); stopping",
                    job.id()
                )
            ).log();
        } catch (final PurgeStore.StoreThrottled throttled) {
            this.jobs.release(
                job.id(), this.node, "storage throttled: " + throttled.getMessage(),
                this.timing.throttled(), false
            );
            RepoJobRunner.log(job, "repo_job_throttled", "unknown",
                String.format(
                    "Repository job %d paused: the storage keeps asking to slow down", job.id()
                )
            ).log();
        } catch (final RuntimeException ex) {
            final Optional<String> state = this.jobs.release(
                job.id(), this.node, String.valueOf(ex.getMessage()),
                this.timing.retry().multipliedBy(1L << Math.min(job.attempts(), 6)), true
            );
            RepoJobRunner.log(job, "repo_job_attempt", "failure",
                String.format(
                    "Repository job %d (%s of '%s') failed in phase %s, attempt %d; now %s",
                    job.id(), job.type().db(), job.name(), job.phase(), job.attempts() + 1,
                    state.orElse("not held")
                )
            ).error(ex).log();
            if (state.filter(RepoJob.FAILED::equals).isPresent()) {
                RepoJobRunner.count("failed", job.type());
            }
        } finally {
            renewal.cancel(false);
        }
    }

    /**
     * Record how a run of a job ended.
     * @param job Job
     * @param outcome How the run ended
     */
    private void settle(final RepoJob job, final Outcome outcome) {
        if (outcome.done()) {
            if (this.jobs.done(job.id(), this.node)) {
                final RepoJob last = this.jobs.get(job.id()).orElse(job);
                RepoJobRunner.log(job, "repo_job_done", "success",
                    String.format(
                        "Repository job %d (%s of '%s') finished: %d objects removed, %d rows",
                        job.id(), job.type().db(), job.name(), last.objects(), last.rows()
                    )
                ).log();
                RepoJobRunner.count("done", job.type());
            }
        } else {
            this.jobs.release(job.id(), this.node, outcome.note(), outcome.delay(), false);
            RepoJobRunner.log(job, "repo_job_wait", "unknown",
                String.format("Repository job %d waits: %s", job.id(), outcome.note())
            ).log();
        }
    }

    /**
     * Purge a deleted repository.
     * @param job Job
     * @return How the run ended
     */
    private Outcome purge(final RepoJob job) {
        final Optional<RepoLifecycleDao.Tombstone> tomb = this.lifecycle.tombstone(job.key());
        final Outcome res;
        if (tomb.isEmpty()) {
            // Purged by an earlier run that died before marking the job done.
            res = Outcome.finished();
        } else {
            final Optional<PurgeStore> store = tomb.get().storage().map(this.stores);
            final Outcome objects = store.isPresent()
                ? this.objects(job, store.get(), tomb.get().age()) : Outcome.finished();
            if (objects.done()) {
                this.rows(job, tomb.get());
                this.lifecycle.forget(job.key());
                res = Outcome.finished();
            } else {
                res = objects;
            }
        }
        return res;
    }

    /**
     * Purge a storage directory no repository owns.
     * @param job Job
     * @return How the run ended
     */
    private Outcome orphan(final RepoJob job) {
        final PurgeStore store = this.stores.apply(job.params().getJsonObject("storage"));
        return this.objects(job, store, job.age());
    }

    /**
     * Remove every object of a directory, then check that none appeared
     * meanwhile.
     * @param job Job
     * @param store Storage
     * @param gone How long ago the directory stopped being served
     * @return Finished, or a wait until late writers have ended
     */
    private Outcome objects(final RepoJob job, final PurgeStore store, final Duration gone) {
        final long begun = System.nanoTime();
        String phase = job.phase();
        Optional<String> after = Optional.ofNullable(job.checkpoint());
        if (RepoJob.PHASE_ROWS.equals(phase)) {
            return Outcome.finished();
        }
        Outcome res = null;
        int passes = 0;
        while (res == null) {
            if (RepoJob.PHASE_OBJECTS.equals(phase)) {
                this.sweep(job, store, after);
                this.throttled(() -> store.abortUploads(job.key()));
                phase = RepoJob.PHASE_VERIFY;
                after = Optional.empty();
                this.progress(job, phase, null, 0, 0);
            }
            final Duration settle = this.timing.settle()
                .minus(gone).minusNanos(System.nanoTime() - begun);
            if (!settle.isNegative() && !settle.isZero()) {
                res = Outcome.later(
                    settle, "letting requests that were in flight at the delete end before the last check"
                );
            } else if (this.throttled(() -> store.list(job.key(), Optional.empty(), 1))
                .names().isEmpty()) {
                this.progress(job, RepoJob.PHASE_ROWS, null, 0, 0);
                res = Outcome.finished();
            } else {
                passes += 1;
                if (passes >= RepoJobRunner.MAX_PASSES) {
                    throw new IllegalStateException(
                        String.format(
                            "Objects keep appearing under '%s' after %d purge passes; something still writes there",
                            job.key(), passes
                        )
                    );
                }
                phase = RepoJob.PHASE_OBJECTS;
                this.progress(job, phase, null, 0, 0);
            }
        }
        return res;
    }

    /**
     * One pass over a directory: list a page, delete it, checkpoint.
     * @param job Job
     * @param store Storage
     * @param from Name to resume after
     */
    private void sweep(final RepoJob job, final PurgeStore store, final Optional<String> from) {
        Optional<String> after = from;
        long total = 0;
        boolean more = true;
        while (more) {
            final Optional<String> pos = after;
            final PurgeStore.Page page = this.throttled(
                () -> store.list(job.key(), pos, RepoJobRunner.PAGE)
            );
            if (!page.names().isEmpty()) {
                this.throttled(() -> {
                    store.delete(page.names());
                    return null;
                });
                total += page.names().size();
                RepoJobRunner.objects(page.names().size());
            }
            after = page.next();
            more = page.next().isPresent();
            this.progress(
                job, RepoJob.PHASE_OBJECTS,
                after.orElseGet(
                    () -> page.names().isEmpty()
                        ? pos.orElse(null) : page.names().get(page.names().size() - 1)
                ),
                page.names().size(), 0
            );
            if (total > 0 && total % (RepoJobRunner.PAGE * 100L) < RepoJobRunner.PAGE) {
                RepoJobRunner.log(job, "repo_job_progress", "unknown",
                    String.format(
                        "Repository job %d: %d objects removed in this pass", job.id(), total
                    )
                ).log();
            }
        }
    }

    /**
     * Delete the database rows of a purged repository in batches: its index
     * rows (keyed by the repository key) and the cooldown history written
     * under its name before it was deleted.
     * @param job Job
     * @param tomb Tombstone
     */
    private void rows(final RepoJob job, final RepoLifecycleDao.Tombstone tomb) {
        this.batches(
            job,
            "DELETE FROM artifacts WHERE id IN"
                + " (SELECT id FROM artifacts WHERE repo_name = ? LIMIT ?)",
            stmt -> stmt.setString(1, tomb.key())
        );
        this.batches(
            job,
            "DELETE FROM artifact_cooldowns_history WHERE id IN"
                + " (SELECT id FROM artifact_cooldowns_history"
                + " WHERE repo_name = ? AND archived_at <= ? LIMIT ?)",
            stmt -> {
                stmt.setString(1, tomb.name());
                stmt.setLong(2, tomb.deletedAt().toEpochMilli());
            }
        );
    }

    /**
     * After a rename: move the cooldown history to the new name in batches.
     * @param job Job
     * @return Finished
     */
    private Outcome followup(final RepoJob job) {
        final String old = job.params().getString("old_name");
        final String target = job.params().getString("new_name");
        this.batches(
            job,
            "UPDATE artifact_cooldowns_history SET repo_name = ? WHERE id IN"
                + " (SELECT id FROM artifact_cooldowns_history"
                + " WHERE repo_name = ? AND archived_at <= ? LIMIT ?)",
            stmt -> {
                stmt.setString(1, target);
                stmt.setString(2, old);
                stmt.setLong(3, job.created().toEpochMilli());
            }
        );
        return Outcome.finished();
    }

    /**
     * Run a row statement in batches until it changes nothing. The last
     * parameter of the statement is the batch size.
     * @param job Job
     * @param sql Statement
     * @param binder Sets every parameter but the last
     */
    private void batches(final RepoJob job, final String sql, final Binder binder) {
        int changed = RepoJobRunner.ROWS;
        while (changed > 0) {
            try (Connection conn = this.source.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
                binder.bind(stmt);
                stmt.setInt(stmt.getParameterMetaData().getParameterCount(), RepoJobRunner.ROWS);
                changed = stmt.executeUpdate();
            } catch (final SQLException ex) {
                throw new IllegalStateException("Row batch of repository job failed", ex);
            }
            this.progress(job, RepoJob.PHASE_ROWS, null, 0, changed);
        }
    }

    /**
     * Record progress; stop when the lease is gone.
     * @param job Job
     * @param phase Phase
     * @param checkpoint Resume position
     * @param objects Objects removed since the last write
     * @param rows Rows changed since the last write
     */
    private void progress(
        final RepoJob job, final String phase, final String checkpoint,
        final long objects, final long rows
    ) {
        if (!this.jobs.progress(
            job.id(), this.node, this.timing.lease(), phase, checkpoint, objects, rows
        )) {
            throw new LeaseLost();
        }
    }

    /**
     * Call the storage, backing off while it asks to slow down.
     * @param call Storage call
     * @param <T> Result type
     * @return Result of the call
     */
    private <T> T throttled(final java.util.function.Supplier<T> call) {
        int attempt = 0;
        while (true) {
            try {
                return call.get();
            } catch (final PurgeStore.StoreThrottled ex) {
                attempt += 1;
                if (attempt >= RepoJobRunner.MAX_THROTTLES) {
                    throw ex;
                }
                RepoJobRunner.count("throttled", null);
                try {
                    Thread.sleep(this.timing.backoff().toMillis() * Math.min(attempt, 8));
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while backing off", interrupted);
                }
            }
        }
    }

    /**
     * Log builder for a job state transition.
     * @param job Job
     * @param action Event action
     * @param outcome Event outcome
     * @param message Message
     * @return Logger to finish with {@code log()}
     */
    private static EcsLogger log(
        final RepoJob job, final String action, final String outcome, final String message
    ) {
        final EcsLogger logger = "failure".equals(outcome)
            ? EcsLogger.error(RepoJobRunner.LOGGER) : EcsLogger.info(RepoJobRunner.LOGGER);
        logger.message(message)
            .eventCategory("process")
            .eventAction(action)
            .eventOutcome(outcome)
            .field("repository.name", job.name())
            .field("log.source", "application");
        if (job.trace() != null) {
            logger.field("trace.id", job.trace());
        }
        return logger;
    }

    /**
     * Count a job event.
     * @param event Event: done, failed, throttled
     * @param type Job type, nullable
     */
    private static void count(final String event, final RepoJob.Type type) {
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().getRegistry()
                .counter(
                    "pantera.repo.jobs.events",
                    "event", event, "type", type == null ? "any" : type.db()
                ).increment();
        }
    }

    /**
     * Count purged objects.
     * @param number Objects removed
     */
    private static void objects(final int number) {
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().getRegistry()
                .counter("pantera.repo.jobs.objects.purged").increment(number);
        }
    }

    /**
     * Daemon thread.
     * @param task Task
     * @param name Thread name
     * @return Thread
     */
    private static Thread thread(final Runnable task, final String name) {
        final Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    /**
     * Sets the parameters of a row statement.
     */
    @FunctionalInterface
    private interface Binder {
        /**
         * Bind.
         * @param stmt Statement
         * @throws SQLException On error
         */
        void bind(PreparedStatement stmt) throws SQLException;
    }

    /**
     * This node no longer holds the job.
     */
    private static final class LeaseLost extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /**
     * How a run of a job ended.
     * @param done Whether the job is finished
     * @param delay When to look at it again, if not
     * @param note Why it waits
     */
    private record Outcome(boolean done, Duration delay, String note) {

        /**
         * The job is finished.
         * @return Outcome
         */
        static Outcome finished() {
            return new Outcome(true, Duration.ZERO, "");
        }

        /**
         * The job has to wait; not a failure.
         * @param delay How long
         * @param note Why
         * @return Outcome
         */
        static Outcome later(final Duration delay, final String note) {
            return new Outcome(false, delay, note);
        }
    }

    /**
     * Timing of the runner.
     * @param poll Interval between looks for due jobs
     * @param lease Lease length; renewed at a third of it
     * @param settle How long a deleted repository must be gone before the
     *  last check: longer than any request that was in flight
     * @param retry Base delay after a failed attempt (doubles per attempt)
     * @param backoff Base pause when the storage asks to slow down
     * @param throttled Delay before a job released for throttling is retried
     */
    public record Timing(
        Duration poll, Duration lease, Duration settle, Duration retry,
        Duration backoff, Duration throttled
    ) {

        /**
         * Production timing.
         * @return Timing
         */
        public static Timing standard() {
            return new Timing(
                Duration.ofSeconds(15), Duration.ofMinutes(2), Duration.ofMinutes(30),
                Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMinutes(1)
            );
        }
    }
}
