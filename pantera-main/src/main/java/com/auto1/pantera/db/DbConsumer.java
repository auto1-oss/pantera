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
package com.auto1.pantera.db;

import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.http.cache.NegativeCacheRegistry;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.trace.TraceContextExecutor;
import com.auto1.pantera.index.ArtifactIndexCache;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.misc.ConfigDefaults;
import org.slf4j.MDC;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Observer;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;

/**
 * Consumer for artifact records which writes the records into db.
 *
 * <p>Multiple distinct error sites in this class — distinct failure
 * modes (batch UPSERT, dead-letter persist, queue back-pressure,
 * shutdown await). Each is the boundary for its respective retry /
 * dead-letter decision so the log lines are intentionally separate.
 * See audit/aggressive-items.md (Tier 4 B7 duplicate-error bucket).
 *
 * @since 0.31
 */
public final class DbConsumer implements Consumer<ArtifactEvent> {

    /**
     * Default buffer time in seconds.
     */
    private static final int DEFAULT_BUFFER_TIME_SECONDS =
        ConfigDefaults.getInt("PANTERA_DB_BUFFER_SECONDS", 2);

    /**
     * Default buffer size (max events per batch).
     */
    private static final int DEFAULT_BUFFER_SIZE =
        ConfigDefaults.getInt("PANTERA_DB_BATCH_SIZE", 200);

    /**
     * Attempts after which an event that keeps failing on its own is
     * dead-lettered instead of re-queued.
     */
    private static final int MAX_EVENT_ATTEMPTS = 3;

    /**
     * SQLSTATE class of data exceptions (e.g. 22021: a NUL byte in a text
     * parameter). Retrying cannot fix such an event.
     */
    private static final String SQLSTATE_DATA_EXCEPTION = "22";

    /**
     * Thread factory for the DbConsumer single-thread scheduler.
     */
    private static final ThreadFactory DB_CONSUMER_TF = runnable -> {
        final Thread thread = new Thread(runnable, "pantera.db-consumer");
        thread.setDaemon(true);
        return thread;
    };

    /**
     * Dedicated RxJava Scheduler backed by a single-thread executor wrapped in
     * {@link TraceContextExecutor} so MDC (trace.id, client.ip, etc.) flows
     * onto the consumer thread. Replaces {@link Schedulers#io()} which loses
     * per-request context.
     */
    private static final Scheduler DB_CONSUMER_SCHEDULER =
        Schedulers.from(
            TraceContextExecutor.wrap(Executors.newSingleThreadExecutor(DB_CONSUMER_TF))
        );

    /**
     * Publish subject
     * <a href="https://reactivex.io/documentation/subject.html">Docs</a>.
     */
    private final PublishSubject<ArtifactEvent> subject;

    /**
     * Database source.
     */
    private final DataSource source;

    /**
     * L1 artifact-index cache, when a DB-backed index is wired. After a batch
     * commits, freshly-inserted names are surgically dropped from the index
     * NEGATIVE tier here so the async proxy-ingestion path gets the same
     * read-after-write self-heal the synchronous hosted path already gets via
     * {@link DbSyncArtifactIndexer}. Empty for index-less deployments/tests.
     */
    private final Optional<ArtifactIndexCache> indexCache;

    /**
     * Fence that drops events superseded by a repository or path delete.
     */
    private final IndexWriteFence fence;

    /**
     * Directory the events that cannot be written are dead-lettered to.
     */
    private final Path deadLetterDir;

    /**
     * Ctor with default buffer settings.
     * @param source Database source
     */
    public DbConsumer(final DataSource source) {
        this(source, DEFAULT_BUFFER_TIME_SECONDS, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Ctor with configurable buffer settings and no index cache.
     * @param source Database source
     * @param bufferTimeSeconds Buffer time in seconds
     * @param bufferSize Maximum events per batch
     */
    public DbConsumer(final DataSource source, final int bufferTimeSeconds, final int bufferSize) {
        this(source, bufferTimeSeconds, bufferSize, Optional.empty());
    }

    /**
     * Ctor with configurable buffer settings and an artifact-index cache for
     * post-commit negative-cache invalidation (Fix 3).
     * @param source Database source
     * @param bufferTimeSeconds Buffer time in seconds
     * @param bufferSize Maximum events per batch
     * @param indexCache L1 artifact-index cache, or empty when none is wired
     */
    public DbConsumer(
        final DataSource source,
        final int bufferTimeSeconds,
        final int bufferSize,
        final Optional<ArtifactIndexCache> indexCache
    ) {
        this(
            source, bufferTimeSeconds, bufferSize, indexCache,
            IndexWriteFence.shared(),
            Path.of(System.getProperty("pantera.home", "/var/pantera")).resolve(".dead-letter")
        );
    }

    /**
     * Primary ctor.
     * @param source Database source
     * @param bufferTimeSeconds Buffer time in seconds
     * @param bufferSize Maximum events per batch
     * @param indexCache L1 artifact-index cache, or empty when none is wired
     * @param fence Fence dropping events superseded by deletes
     * @param deadLetterDir Directory for events that cannot be written
     */
    public DbConsumer(
        final DataSource source,
        final int bufferTimeSeconds,
        final int bufferSize,
        final Optional<ArtifactIndexCache> indexCache,
        final IndexWriteFence fence,
        final Path deadLetterDir
    ) {
        this.source = source;
        this.indexCache = indexCache == null ? Optional.empty() : indexCache;
        this.fence = fence;
        this.deadLetterDir = deadLetterDir;
        this.subject = PublishSubject.create();
        this.subject.subscribeOn(DB_CONSUMER_SCHEDULER)
            .buffer(bufferTimeSeconds, TimeUnit.SECONDS, bufferSize)
            .subscribe(new DbObserver());
    }

    @Override
    public void accept(final ArtifactEvent record) {
        this.subject.onNext(record);
    }

    /**
     * Normalize repository name by trimming whitespace.
     * This ensures data consistency and allows index usage in queries.
     * @param name Repository name
     * @return Normalized name
     */
    private static String normalizeRepoName(final String name) {
        return name == null ? null : name.trim();
    }

    /**
     * Fix 3: after a committed batch, drop stale negative-cache entries for the
     * artifacts that just landed in the index.
     *
     * <p>Two negative caches are cleared, both keyed by artifact name only:
     * <ul>
     *   <li>the request-level {@code repo-negative} cache — via one batched L1
     *       scan for all distinct names (never per-name; L1 is sized up to 200k
     *       in prod, so per-name scans would exceed the flush cadence);</li>
     *   <li>the {@code artifact-index-negative} tier — surgically per
     *       (name, repo) via {@link ArtifactIndexCache#recordUpload}, mirroring
     *       the synchronous hosted path so proxy-ingested artifacts self-heal
     *       instead of waiting out the index-negative TTL.</li>
     * </ul>
     *
     * <p>Only committed INSERT events are considered (DELETE events and the
     * savepoint-rolled-back {@code errors} rows are excluded). Never throws.
     *
     * @param committedBatch the batch that just committed
     * @param failed rows that were rolled back within the batch (excluded)
     */
    private void invalidateAfterCommit(
        final List<ArtifactEvent> committedBatch,
        final List<ArtifactEvent> failed
    ) {
        final Set<String> names = new HashSet<>();
        final Set<String> nameRepoPairs = new HashSet<>();
        for (final ArtifactEvent record : committedBatch) {
            if (record.eventType() != ArtifactEvent.Type.INSERT
                || failed.contains(record)) {
                continue;
            }
            final String name = record.artifactName();
            if (name == null || name.isEmpty()) {
                continue;
            }
            names.add(name);
            final String repo = normalizeRepoName(record.repoName());
            if (this.indexCache.isPresent() && nameRepoPairs.add(name + ' ' + repo)) {
                this.indexCache.get().recordUpload(name, repo);
            }
        }
        if (!names.isEmpty()) {
            NegativeCacheRegistry.instance().invalidateAfterUploadBatch(names);
        }
    }

    /**
     * Emit ECS audit log for successful artifact publish operations.
     *
     * <p>{@link AuditLogger} takes {@code trace.id} / {@code client.ip} as
     * explicit {@link AuditContext} parameters — no MDC read inside
     * {@code AuditLogger} itself. This method still binds the two fields into
     * MDC (saved/restored around the call) for the DB-consumer thread: {@code
     * RxComputationThreadPool} threads are pooled and reused across many
     * unrelated batches, so a value left over from a previous call's {@code
     * ctx} would otherwise win over this call's explicit value inside {@link
     * EcsLogger#field}'s MDC-wins rule (it drops the field() value whenever
     * {@code ThreadContext} already has that key — correct for the
     * synchronous single-request case {@code EcsLoggingSlice} was designed
     * for, but a hazard on a shared thread pool with no request-scoped MDC
     * lifecycle). Binding this call's own value before invoking {@link
     * AuditLogger#publish} guarantees the two sources always agree.
     *
     * @param record Artifact event that was persisted
     */
    private static void logArtifactPublish(final ArtifactEvent record) {
        final String priorTraceId = MDC.get(EcsMdc.TRACE_ID);
        final String priorClientIp = MDC.get(EcsMdc.CLIENT_IP);
        final String eventTraceId = record.traceId();
        final String eventClientIp = record.clientIp();
        if (eventTraceId != null) {
            MDC.put(EcsMdc.TRACE_ID, eventTraceId);
        }
        if (eventClientIp != null) {
            MDC.put(EcsMdc.CLIENT_IP, eventClientIp);
        }
        try {
            AuditLogger.publish(
                new AuditContext(eventTraceId, eventClientIp),
                record.repoType(),
                normalizeRepoName(record.repoName()),
                record.artifactName(),
                record.artifactVersion(),
                record.size(),
                record.owner(),
                record.releaseDate().orElse(null),
                record.checksum(),
                AuditLogger.OUTCOME_SUCCESS,
                null
            );
        } finally {
            if (priorTraceId != null) {
                MDC.put(EcsMdc.TRACE_ID, priorTraceId);
            } else if (eventTraceId != null) {
                MDC.remove(EcsMdc.TRACE_ID);
            }
            if (priorClientIp != null) {
                MDC.put(EcsMdc.CLIENT_IP, priorClientIp);
            } else if (eventClientIp != null) {
                MDC.remove(EcsMdc.CLIENT_IP);
            }
        }
    }

    /**
     * Database observer. Writes pack into database.
     * @since 0.31
     */
    private final class DbObserver implements Observer<List<ArtifactEvent>> {

        /**
         * Tracks consecutive batch commit failures to prevent infinite re-queuing.
         * Reset to 0 on successful commit; events are dropped after 3 consecutive failures.
         */
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

        /**
         * Failed attempts of events that failed on their own (the batch
         * committed). Bounded: an entry leaves on success or dead-letter.
         */
        private final Map<ArtifactEvent, Integer> attempts =
            Collections.synchronizedMap(new IdentityHashMap<>());

        @Override
        public void onSubscribe(final @NonNull Disposable disposable) {
            EcsLogger.debug("com.auto1.pantera.db")
                .message("Subscribed to insert/delete db records")
                .eventCategory("database")
                .eventAction("subscription_start")
                .field("log.source", "application")
                .log();
        }

        @Override
        public void onNext(final @NonNull List<ArtifactEvent> events) {
            if (events.isEmpty()) {
                return;
            }
            // Sort events by (repo_name, name, version) to ensure consistent lock ordering
            // This prevents deadlocks when multiple transactions process overlapping artifacts
            final List<ArtifactEvent> sortedEvents = new ArrayList<>(events);
            sortedEvents.sort((a, b) -> {
                int cmp = a.repoName().compareTo(b.repoName());
                if (cmp != 0) return cmp;
                cmp = a.artifactName().compareTo(b.artifactName());
                if (cmp != 0) return cmp;
                return a.artifactVersion().compareTo(b.artifactVersion());
            });
            final List<ArtifactEvent> errors = new ArrayList<>(sortedEvents.size());
            final List<ArtifactEvent> retry = new ArrayList<>(0);
            final List<ArtifactEvent> dead = new ArrayList<>(0);
            SQLException lastError = null;
            boolean error = false;
            try (
                IndexWriteFence.Held held = DbConsumer.this.fence.writing(); // NOPMD UnusedLocalVariable - holds the fence's writer side for the batch
                Connection conn = DbConsumer.this.source.getConnection();
                PreparedStatement upsert = conn.prepareStatement(
                    "INSERT INTO artifacts (repo_type, repo_name, name, version, size, created_date, release_date, owner, path_prefix) " +
                    "VALUES (?,?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT (repo_name, name, version) " +
                    "DO UPDATE SET repo_type = EXCLUDED.repo_type, size = EXCLUDED.size, " +
                    "created_date = EXCLUDED.created_date, release_date = EXCLUDED.release_date, " +
                    "owner = EXCLUDED.owner, path_prefix = COALESCE(EXCLUDED.path_prefix, artifacts.path_prefix)"
                );
                PreparedStatement publishDate = conn.prepareStatement(
                    "INSERT INTO artifact_publish_dates (repo_type, name, version, published_at, source) "
                    + "VALUES (?,?,?,?,'cache_write_event') "
                    + "ON CONFLICT (repo_type, name, version) DO NOTHING"
                );
                PreparedStatement deletev = conn.prepareStatement(
                    "DELETE FROM artifacts WHERE repo_name = ? AND name = ? AND version = ?;"
                );
                PreparedStatement delete = conn.prepareStatement(
                    "DELETE FROM artifacts WHERE repo_name = ? AND name = ?;"
                )
            ) {
                conn.setAutoCommit(false);
                for (final ArtifactEvent record : sortedEvents) {
                    if (DbConsumer.this.fence.fenced(record)) {
                        // A repository or path delete ran after this upload
                        // was queued: writing it now would resurrect the row.
                        // The upload itself did happen, so it is still audited.
                        this.attempts.remove(record);
                        if (record.eventType() == ArtifactEvent.Type.INSERT) {
                            logArtifactPublish(record);
                        }
                        errors.add(record);
                        continue;
                    }
                    // Use a SAVEPOINT so a single statement failure (e.g.
                    // PK collision from a stale sequence) does not abort
                    // the entire transaction and poison all subsequent
                    // events in the batch.
                    java.sql.Savepoint sp = null;
                    try {
                        sp = conn.setSavepoint();
                        if (record.eventType() == ArtifactEvent.Type.INSERT) {
                            // Use atomic UPSERT to prevent deadlocks
                            final long release = record.releaseDate().orElse(record.createdDate());
                            upsert.setString(1, record.repoType());
                            upsert.setString(2, normalizeRepoName(record.repoName()));
                            upsert.setString(3, record.artifactName());
                            upsert.setString(4, record.artifactVersion());
                            upsert.setDouble(5, record.size());
                            upsert.setLong(6, record.createdDate());
                            upsert.setLong(7, release);
                            upsert.setString(8, record.owner());
                            upsert.setString(9, record.pathPrefix());
                            upsert.execute();
                            // Bridge: when the event carries an authoritative release_date,
                            // mirror it into the canonical artifact_publish_dates lookup so the
                            // cooldown subsystem has a date for newly-cached artifacts without
                            // a second upstream HEAD. ON CONFLICT preserves registry-sourced
                            // rows. Same transaction — artifacts UPSERT rollback also rolls
                            // back the bridge insert.
                            if (record.releaseDate().isPresent()) {
                                publishDate.setString(1, record.repoType());
                                publishDate.setString(2, record.artifactName());
                                publishDate.setString(3, record.artifactVersion());
                                publishDate.setTimestamp(
                                    4,
                                    new java.sql.Timestamp(record.releaseDate().get())
                                );
                                publishDate.execute();
                            }
                            conn.releaseSavepoint(sp);
                            this.attempts.remove(record);
                            logArtifactPublish(record);
                        } else if (record.eventType() == ArtifactEvent.Type.DELETE_VERSION) {
                            deletev.setString(1, normalizeRepoName(record.repoName()));
                            deletev.setString(2, record.artifactName());
                            deletev.setString(3, record.artifactVersion());
                            deletev.execute();
                            conn.releaseSavepoint(sp);
                        } else if (record.eventType() == ArtifactEvent.Type.DELETE_ALL) {
                            delete.setString(1, normalizeRepoName(record.repoName()));
                            delete.setString(2, record.artifactName());
                            delete.execute();
                            conn.releaseSavepoint(sp);
                        }
                    } catch (final SQLException ex) {
                        if (sp != null) {
                            try {
                                conn.rollback(sp);
                            } catch (final SQLException rollbackEx) {
                                ex.addSuppressed(rollbackEx);
                            }
                        }
                        EcsLogger.error("com.auto1.pantera.db")
                            .message("Failed to process artifact event")
                            .eventCategory("database")
                            .eventAction("artifact_event_process")
                            .eventOutcome("failure")
                            .field("event.reason", ex.getSQLState())
                            .field("repository.name", record.repoName())
                            .field("package.name", record.artifactName())
                            .error(ex)
                            .field("log.source", "application")
                            .log();
                        errors.add(record);
                        lastError = ex;
                        if (this.retryable(record, ex)) {
                            retry.add(record);
                        } else {
                            dead.add(record);
                        }
                    }
                }
                conn.commit();
                this.consecutiveFailures.set(0);
            } catch (final SQLException ex) {
                final int failures = this.consecutiveFailures.incrementAndGet();
                if (failures <= 3) {
                    EcsLogger.error("com.auto1.pantera.db")
                        .message("Batch commit failed, re-queuing " + sortedEvents.size()
                            + " events (attempt " + failures + "/3)")
                        .eventCategory("database")
                        .eventAction("batch_commit")
                        .eventOutcome("failure")
                        .error(ex)
                        .field("log.source", "application")
                        .log();
                    final long backoffMs = Math.min(
                        1000L * (1L << (failures - 1)), 8000L
                    );
                    try {
                        Thread.sleep(backoffMs);
                    } catch (final InterruptedException ie) {
                        // EXPECTED: shutdown signalled mid-backoff —
                        // restore interrupt and let the re-queue happen
                        // (the consumer thread will exit on its own
                        // shutdown check).
                        Thread.currentThread().interrupt();
                    }
                    sortedEvents.forEach(DbConsumer.this.subject::onNext);
                } else {
                    EcsLogger.error("com.auto1.pantera.db")
                        .message("Writing " + sortedEvents.size()
                            + " events to dead-letter after " + failures
                            + " consecutive batch failures")
                        .eventCategory("database")
                        .eventAction("batch_dead_letter")
                        .eventOutcome("failure")
                        .error(ex)
                        .field("log.source", "application")
                        .log();
                    try {
                        final DeadLetterWriter dlWriter =
                            new DeadLetterWriter(DbConsumer.this.deadLetterDir);
                        dlWriter.write(sortedEvents, ex, failures);
                    } catch (final IOException dlError) {
                        EcsLogger.error("com.auto1.pantera.db")
                            .message(String.format(
                                "Failed to write dead-letter file, dropping %d events",
                                sortedEvents.size()))
                            .eventCategory("database")
                            .eventAction("dead_letter_write")
                            .eventOutcome("failure")
                            .error(dlError)
                            .field("log.source", "application")
                            .log();
                    }
                }
                error = true;
            }
            if (!error) {
                // An event that failed on its own is retried a bounded number
                // of times; one that can never be written (a data exception,
                // e.g. a NUL byte in its name) is dead-lettered at once. Never
                // re-queued forever: that logged an ERROR every batch window.
                retry.forEach(DbConsumer.this.subject::onNext);
                if (!dead.isEmpty()) {
                    this.deadLetter(dead, lastError);
                }
            }
            // Fix 3: after a successful batch commit, drop any stale 404 for
            // the freshly-indexed artifacts. This is the ONLY invalidation the
            // proxy fetch-and-store path gets — hosted publishes invalidate
            // synchronously in their upload slices, but proxy ingestion flows
            // only through this async batcher. Runs AFTER the try-with-resources
            // (connection released) so the O(L1) negative-cache scan never holds
            // a pooled connection. Skipped entirely when the batch failed to
            // commit (error) — those events re-queue and invalidate on retry.
            if (!error) {
                DbConsumer.this.invalidateAfterCommit(sortedEvents, errors);
            }
        }

        /**
         * Count a failed attempt of an event and decide whether to retry it.
         * @param record Failed event
         * @param err Its failure
         * @return True to re-queue, false to dead-letter
         */
        private boolean retryable(final ArtifactEvent record, final SQLException err) {
            final int tries = this.attempts.merge(record, 1, Integer::sum);
            final String state = err.getSQLState();
            final boolean permanent = state != null
                && state.startsWith(DbConsumer.SQLSTATE_DATA_EXCEPTION);
            final boolean again = !permanent && tries < DbConsumer.MAX_EVENT_ATTEMPTS;
            if (!again) {
                this.attempts.remove(record);
            }
            return again;
        }

        /**
         * Dead-letter events that cannot be written.
         * @param dead Events
         * @param err Last failure
         */
        private void deadLetter(final List<ArtifactEvent> dead, final SQLException err) {
            EcsLogger.error("com.auto1.pantera.db")
                .message("Writing " + dead.size()
                    + " artifact events that cannot be indexed to dead-letter")
                .eventCategory("database")
                .eventAction("event_dead_letter")
                .eventOutcome("failure")
                .error(err)
                .field("log.source", "application")
                .log();
            try {
                new DeadLetterWriter(DbConsumer.this.deadLetterDir)
                    .write(dead, err, DbConsumer.MAX_EVENT_ATTEMPTS);
            } catch (final IOException dlError) {
                EcsLogger.error("com.auto1.pantera.db")
                    .message(String.format(
                        "Failed to write dead-letter file, dropping %d events", dead.size()))
                    .eventCategory("database")
                    .eventAction("dead_letter_write")
                    .eventOutcome("failure")
                    .error(dlError)
                    .field("log.source", "application")
                    .log();
            }
        }

        @Override
        public void onError(final @NonNull Throwable error) {
            EcsLogger.error("com.auto1.pantera.db")
                .message("Fatal error in database consumer")
                .eventCategory("database")
                .eventAction("subscription_error")
                .eventOutcome("failure")
                .error(error)
                .field("log.source", "application")
                .log();
        }

        @Override
        public void onComplete() {
            EcsLogger.debug("com.auto1.pantera.db")
                .message("Subscription cancelled")
                .eventCategory("database")
                .eventAction("subscription_complete")
                .field("log.source", "application")
                .log();
        }
    }
}
