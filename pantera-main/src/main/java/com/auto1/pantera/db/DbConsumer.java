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

import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.http.trace.TraceContextExecutor;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.misc.ConfigDefaults;
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
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;

/**
 * Consumer for artifact records which writes the records into db.
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
     * Ctor with default buffer settings.
     * @param source Database source
     */
    public DbConsumer(final DataSource source) {
        this(source, DEFAULT_BUFFER_TIME_SECONDS, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Ctor with configurable buffer settings.
     * @param source Database source
     * @param bufferTimeSeconds Buffer time in seconds
     * @param bufferSize Maximum events per batch
     */
    @SuppressWarnings("PMD.ConstructorOnlyInitializesOrCallOtherConstructors")
    public DbConsumer(final DataSource source, final int bufferTimeSeconds, final int bufferSize) {
        this.source = source;
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
     * Emit ECS audit log for successful artifact publish operations.
     * @param record Artifact event that was persisted
     */
    private static void logArtifactPublish(final ArtifactEvent record) {
        AuditLogger.publish(
            normalizeRepoName(record.repoName()),
            record.repoType(),
            record.artifactName(),
            record.artifactVersion(),
            record.size(),
            record.owner(),
            record.releaseDate().orElse(null)
        );
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

        @Override
        public void onSubscribe(final @NonNull Disposable disposable) {
            EcsLogger.debug("com.auto1.pantera.db")
                .message("Subscribed to insert/delete db records")
                .eventCategory("database")
                .eventAction("subscription_start")
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
            boolean error = false;
            try (
                Connection conn = DbConsumer.this.source.getConnection();
                PreparedStatement upsert = conn.prepareStatement(
                    "INSERT INTO artifacts (repo_type, repo_name, name, version, size, created_date, release_date, owner, path_prefix) " +
                    "VALUES (?,?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT (repo_name, name, version) " +
                    "DO UPDATE SET repo_type = EXCLUDED.repo_type, size = EXCLUDED.size, " +
                    "created_date = EXCLUDED.created_date, release_date = EXCLUDED.release_date, " +
                    "owner = EXCLUDED.owner, path_prefix = COALESCE(EXCLUDED.path_prefix, artifacts.path_prefix)"
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
                            conn.releaseSavepoint(sp);
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
                            .field("repository.name", record.repoName())
                            .field("package.name", record.artifactName())
                            .error(ex)
                            .log();
                        errors.add(record);
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
                        .log();
                    final long backoffMs = Math.min(
                        1000L * (1L << (failures - 1)), 8000L
                    );
                    try {
                        Thread.sleep(backoffMs);
                    } catch (final InterruptedException ie) {
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
                        .log();
                    try {
                        final DeadLetterWriter dlWriter = new DeadLetterWriter(
                            Path.of(System.getProperty(
                                "pantera.home", "/var/pantera"
                            )).resolve(".dead-letter")
                        );
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
                            .log();
                    }
                }
                error = true;
            }
            if (!error && !errors.isEmpty()) {
                if (errors.size() <= 5) {
                    // Only re-queue a small number of individual errors
                    errors.forEach(DbConsumer.this.subject::onNext);
                } else {
                    EcsLogger.error("com.auto1.pantera.db")
                        .message("Dropping " + errors.size()
                            + " individually failed events (too many errors in batch)")
                        .eventCategory("database")
                        .eventAction("event_drop")
                        .eventOutcome("failure")
                        .log();
                }
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
                .log();
        }

        @Override
        public void onComplete() {
            EcsLogger.debug("com.auto1.pantera.db")
                .message("Subscription cancelled")
                .eventCategory("database")
                .eventAction("subscription_complete")
                .log();
        }
    }
}
