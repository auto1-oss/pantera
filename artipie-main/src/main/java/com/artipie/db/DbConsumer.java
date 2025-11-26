/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.db;

import com.artipie.scheduling.ArtifactEvent;
import com.artipie.http.log.EcsLogger;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Observer;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
    private static final int DEFAULT_BUFFER_TIME_SECONDS = 2;

    /**
     * Default buffer size (max events per batch).
     */
    private static final int DEFAULT_BUFFER_SIZE = 50;

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
        this.subject.subscribeOn(Schedulers.io())
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
        final EcsLogger logger = EcsLogger.info("com.artipie.audit")
            .message("Artifact publish recorded")
            .eventCategory("artifact")
            .eventAction("artifact_publish")
            .eventOutcome("success")
            .field("repository.type", record.repoType())
            .field("repository.name", normalizeRepoName(record.repoName()))
            .field("package.name", record.artifactName())
            .field("package.version", record.artifactVersion())
            .field("package.size", record.size())
            .userName(record.owner());
        record.releaseDate().ifPresent(release -> logger.field("artifact.release", release));
        logger.log();
    }

    /**
     * Database observer. Writes pack into database.
     * @since 0.31
     */
    private final class DbObserver implements Observer<List<ArtifactEvent>> {

        @Override
        public void onSubscribe(final @NonNull Disposable disposable) {
            EcsLogger.debug("com.artipie.db")
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
            final List<ArtifactEvent> errors = new ArrayList<>(events.size());
            boolean error = false;
            try (
                Connection conn = DbConsumer.this.source.getConnection();
                PreparedStatement upsert = conn.prepareStatement(
                    "INSERT INTO artifacts (repo_type, repo_name, name, version, size, created_date, release_date, owner) " +
                    "VALUES (?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT (repo_name, name, version) " +
                    "DO UPDATE SET repo_type = EXCLUDED.repo_type, size = EXCLUDED.size, " +
                    "created_date = EXCLUDED.created_date, release_date = EXCLUDED.release_date, owner = EXCLUDED.owner"
                );
                PreparedStatement deletev = conn.prepareStatement(
                    "DELETE FROM artifacts WHERE repo_name = ? AND name = ? AND version = ?;"
                );
                PreparedStatement delete = conn.prepareStatement(
                    "DELETE FROM artifacts WHERE repo_name = ? AND name = ?;"
                )
            ) {
                conn.setAutoCommit(false);
                for (final ArtifactEvent record : events) {
                    try {
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
                            upsert.execute();
                            logArtifactPublish(record);
                        } else if (record.eventType() == ArtifactEvent.Type.DELETE_VERSION) {
                            deletev.setString(1, normalizeRepoName(record.repoName()));
                            deletev.setString(2, record.artifactName());
                            deletev.setString(3, record.artifactVersion());
                            deletev.execute();
                        } else if (record.eventType() == ArtifactEvent.Type.DELETE_ALL) {
                            delete.setString(1, normalizeRepoName(record.repoName()));
                            delete.setString(2, record.artifactName());
                            delete.execute();
                        }
                    } catch (final SQLException ex) {
                        EcsLogger.error("com.artipie.db")
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
            } catch (final SQLException ex) {
                EcsLogger.error("com.artipie.db")
                    .message("Failed to commit artifact events batch (" + events.size() + " events)")
                    .eventCategory("database")
                    .eventAction("batch_commit")
                    .eventOutcome("failure")
                    .error(ex)
                    .log();
                events.forEach(DbConsumer.this.subject::onNext);
                error = true;
            }
            if (!error) {
                errors.forEach(DbConsumer.this.subject::onNext);
            }
        }

        @Override
        public void onError(final @NonNull Throwable error) {
            EcsLogger.error("com.artipie.db")
                .message("Fatal error in database consumer")
                .eventCategory("database")
                .eventAction("subscription_error")
                .eventOutcome("failure")
                .error(error)
                .log();
        }

        @Override
        public void onComplete() {
            EcsLogger.debug("com.artipie.db")
                .message("Subscription cancelled")
                .eventCategory("database")
                .eventAction("subscription_complete")
                .log();
        }
    }
}
