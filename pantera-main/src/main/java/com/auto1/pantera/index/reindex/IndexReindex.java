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
package com.auto1.pantera.index.reindex;

import com.auto1.pantera.backfill.ArtifactRecord;
import com.auto1.pantera.backfill.Scanner;
import com.auto1.pantera.backfill.ScannerFactory;
import com.auto1.pantera.http.log.EcsLogger;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Stream;
import org.slf4j.MDC;

/**
 * Rebuilds the artifact search index from repository storage.
 *
 * <p>A run has two phases:</p>
 * <ol>
 *   <li><b>Prune</b> — delete, in bounded batches, the rows of every
 *   repository that is no longer configured. Never runs when the
 *   repository list is empty (a failed listing must not wipe the index).</li>
 *   <li><b>Rebuild</b> — for every configured repository with local
 *   file-system storage and a backfill scanner for its type: scan the
 *   storage, upsert the rows (sorted by name and version, the DbConsumer
 *   lock order), then delete the repository's artifact rows the scan did
 *   not produce. Repositories without local storage (S3, group) or
 *   without a scanner are skipped with a logged reason and left untouched.</li>
 * </ol>
 *
 * <p>Runs execute on a dedicated single thread — never the event loop, never
 * the API worker pool — one at a time per process (see {@link #start}) and
 * one at a time per cluster (the store's lock).</p>
 *
 * @since 2.2.9
 */
public final class IndexReindex implements AutoCloseable {

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.index";

    /**
     * Default batch size for upserts and deletes.
     */
    private static final int BATCH = 500;

    /**
     * DbConsumer lock order inside one repository.
     */
    private static final Comparator<ArtifactRecord> ORDER = Comparator
        .comparing(ArtifactRecord::name)
        .thenComparing(ArtifactRecord::version);

    /**
     * Index table.
     */
    private final ReindexStore store;

    /**
     * Configured repositories.
     */
    private final ReindexRepos repos;

    /**
     * Scanner per repository type; throws IllegalArgumentException for a
     * type without one.
     */
    private final Function<String, Scanner> scanners;

    /**
     * Single rebuild thread.
     */
    private final ExecutorService executor;

    /**
     * Rows per batch.
     */
    private final int batch;

    /**
     * Whether a run is in progress in this process.
     */
    private final AtomicBoolean running;

    /**
     * Counters of the current or last run.
     */
    private final ReindexProgress progress;

    /**
     * Ctor with the backfill scanners and a dedicated rebuild thread.
     * @param store Index table
     * @param repos Configured repositories
     */
    public IndexReindex(final ReindexStore store, final ReindexRepos repos) {
        this(
            store, repos, ScannerFactory::create,
            Executors.newSingleThreadExecutor(
                runnable -> {
                    final Thread thread = new Thread(runnable, "pantera-index-reindex");
                    thread.setDaemon(true);
                    return thread;
                }
            ),
            IndexReindex.BATCH
        );
    }

    /**
     * Ctor.
     * @param store Index table
     * @param repos Configured repositories
     * @param scanners Scanner per repository type
     * @param executor Rebuild thread
     * @param batch Rows per batch
     */
    IndexReindex(
        final ReindexStore store,
        final ReindexRepos repos,
        final Function<String, Scanner> scanners,
        final ExecutorService executor,
        final int batch
    ) {
        this.store = store;
        this.repos = repos;
        this.scanners = scanners;
        this.executor = executor;
        this.batch = batch;
        this.running = new AtomicBoolean(false);
        this.progress = new ReindexProgress();
    }

    /**
     * Start a run in the background. Non-blocking.
     * @param actor User who triggered it
     * @return False when a run is already in progress in this process
     */
    public boolean start(final String actor) {
        if (!this.running.compareAndSet(false, true)) {
            return false;
        }
        this.progress.reset(Instant.now());
        final Map<String, String> mdc = MDC.getCopyOfContextMap();
        try {
            this.executor.execute(() -> this.runWith(mdc, actor));
        } catch (final RejectedExecutionException ex) {
            this.progress.finish(Instant.now(), "reindex executor is shut down");
            this.running.set(false);
            return false;
        }
        return true;
    }

    /**
     * Current status.
     * @return Status snapshot
     */
    public ReindexStatus status() {
        return this.progress.snapshot(this.running.get());
    }

    @Override
    public void close() {
        this.executor.shutdownNow();
    }

    /**
     * Run with the caller's logging context (trace id, client ip).
     * @param mdc Caller MDC, may be null
     * @param actor User who triggered the run
     */
    private void runWith(final Map<String, String> mdc, final String actor) {
        if (mdc != null) {
            MDC.setContextMap(mdc);
        }
        try {
            this.run(actor);
        } finally {
            this.running.set(false);
            MDC.clear();
        }
    }

    /**
     * One run: take the cluster lock, prune, rebuild.
     * @param actor User who triggered the run
     */
    private void run(final String actor) {
        final long begin = System.currentTimeMillis();
        EcsLogger.info(IndexReindex.LOGGER)
            .message("Search index rebuild started")
            .eventCategory("database")
            .eventAction("search_reindex_start")
            .field("user.name", actor)
            .field("log.source", "application")
            .log();
        String error = null;
        try {
            final Optional<ReindexStore.Lease> lease = this.store.lock();
            if (lease.isEmpty()) {
                error = "a search index rebuild is already running on another node";
            } else {
                final ReindexStore.Lease held = lease.get();
                try (held) {
                    this.prune();
                    this.rebuild();
                }
            }
        } catch (final Exception | Error ex) {
            error = IndexReindex.describe(ex);
        }
        this.progress.finish(Instant.now(), error);
        final ReindexStatus status = this.progress.snapshot(false);
        this.running.set(false);
        this.logFinished(status, error, System.currentTimeMillis() - begin);
    }

    /**
     * Phase 1: delete the rows of repositories that no longer exist.
     * @throws Exception On database or repository listing error
     */
    private void prune() throws Exception {
        final Set<String> known = new HashSet<>(this.repos.names());
        if (known.isEmpty()) {
            EcsLogger.warn(IndexReindex.LOGGER)
                .message("Search index prune skipped: no repositories are configured")
                .eventCategory("database")
                .eventAction("search_reindex_prune")
                .eventOutcome("unknown")
                .field("event.reason", "empty repository list")
                .field("log.source", "application")
                .log();
            return;
        }
        for (final String repo : this.store.indexedRepos()) {
            if (!known.contains(repo)) {
                this.pruneRepo(repo);
            }
        }
    }

    /**
     * Delete one deleted repository's rows, re-checking before every batch
     * that it was not re-created meanwhile.
     * @param repo Repository name
     * @throws Exception On database error
     */
    private void pruneRepo(final String repo) throws Exception {
        long total = 0L;
        long rows = 1L;
        while (rows > 0L && !this.repos.exists(repo)) {
            rows = this.store.pruneBatch(repo, this.batch);
            total += rows;
            this.progress.pruned(rows);
        }
        EcsLogger.info(IndexReindex.LOGGER)
            .message(String.format("Pruned %d index rows of deleted repository", total))
            .eventCategory("database")
            .eventAction("search_reindex_prune")
            .eventOutcome("success")
            .field("repository.name", repo)
            .field("log.source", "application")
            .log();
    }

    /**
     * Phase 2: rebuild every configured repository.
     */
    private void rebuild() {
        final List<String> names = new ArrayList<>(this.repos.names());
        names.sort(Comparator.naturalOrder());
        this.progress.total(names.size());
        for (final String name : names) {
            this.reindexRepo(name);
        }
    }

    /**
     * Rebuild one repository; a failure is recorded and the run continues.
     * @param name Repository name
     */
    private void reindexRepo(final String name) {
        try {
            final ReindexRepos.Target target = this.repos.target(name);
            final Optional<Scanner> scanner = this.scanner(target);
            if (target.skip() != null) {
                this.skip(name, target.type(), target.skip());
            } else if (scanner.isEmpty()) {
                this.skip(
                    name, target.type(),
                    String.format("no storage scanner for repository type '%s'", target.type())
                );
            } else if (!Files.isDirectory(target.root())) {
                this.skip(name, target.type(), "storage directory does not exist yet");
            } else {
                this.reconcile(name, target, scanner.get());
            }
        } catch (final Exception ex) {
            final String message = String.format("%s: %s", name, IndexReindex.describe(ex));
            this.progress.repoFailed(message);
            EcsLogger.error(IndexReindex.LOGGER)
                .message("Search index rebuild of repository failed")
                .eventCategory("database")
                .eventAction("search_reindex_repo")
                .eventOutcome("failure")
                .field("repository.name", name)
                .error(ex)
                .field("log.source", "application")
                .log();
        }
    }

    /**
     * Scanner for a target.
     * @param target Target
     * @return Scanner, empty when the type has none
     */
    private Optional<Scanner> scanner(final ReindexRepos.Target target) {
        Optional<Scanner> res = Optional.empty();
        if (target.skip() == null && target.type() != null) {
            try {
                res = Optional.of(
                    this.scanners.apply(target.type().toLowerCase(Locale.ROOT))
                );
            } catch (final IllegalArgumentException ex) {
                res = Optional.empty();
            }
        }
        return res;
    }

    /**
     * Scan, upsert and remove what the scan did not see.
     * @param name Repository name
     * @param target Target
     * @param scanner Scanner
     * @throws Exception On scan or database error
     */
    private void reconcile(
        final String name, final ReindexRepos.Target target, final Scanner scanner
    ) throws Exception {
        final long begin = System.currentTimeMillis();
        long upserted = 0L;
        long removed = 0L;
        try (ReindexStore.Session session = this.store.open(name, begin)) {
            upserted += this.upsertAll(session, scanner, target, name);
            if (this.repos.exists(name)) {
                long rows = 1L;
                while (rows > 0L) {
                    rows = session.removeUnseen(this.batch);
                    removed += rows;
                    this.progress.removed(rows);
                }
            }
        }
        if (!this.repos.exists(name)) {
            // Deleted while it was being scanned: drop what the scan wrote.
            this.pruneRepo(name);
        }
        this.progress.repoDone();
        EcsLogger.info(IndexReindex.LOGGER)
            .message(
                String.format(
                    "Search index rebuilt for repository: %d rows upserted, %d stale rows removed",
                    upserted, removed
                )
            )
            .eventCategory("database")
            .eventAction("search_reindex_repo")
            .eventOutcome("success")
            .field("repository.name", name)
            .duration(System.currentTimeMillis() - begin)
            .field("log.source", "application")
            .log();
    }

    /**
     * Stream the scan into sorted, bounded upsert batches.
     * @param session Session
     * @param scanner Scanner
     * @param target Target
     * @param name Repository name
     * @return Rows upserted
     * @throws Exception On scan or database error
     */
    private long upsertAll(
        final ReindexStore.Session session, final Scanner scanner,
        final ReindexRepos.Target target, final String name
    ) throws Exception {
        long upserted = 0L;
        final List<ArtifactRecord> buffer = new ArrayList<>(this.batch);
        try (Stream<ArtifactRecord> records = scanner.scan(target.root(), name)) {
            final Iterator<ArtifactRecord> iter = records.iterator();
            while (iter.hasNext()) {
                final ArtifactRecord rec = iter.next();
                if (rec.name() != null && rec.version() != null) {
                    buffer.add(rec);
                }
                if (buffer.size() >= this.batch) {
                    upserted += this.flush(session, buffer);
                }
            }
        }
        upserted += this.flush(session, buffer);
        return upserted;
    }

    /**
     * Upsert and clear the buffer.
     * @param session Session
     * @param buffer Buffered records
     * @return Rows upserted
     * @throws java.sql.SQLException On database error
     */
    private long flush(
        final ReindexStore.Session session, final List<ArtifactRecord> buffer
    ) throws java.sql.SQLException {
        long rows = 0L;
        if (!buffer.isEmpty()) {
            final List<ArtifactRecord> sorted = new ArrayList<>(buffer);
            sorted.sort(IndexReindex.ORDER);
            buffer.clear();
            rows = session.upsert(sorted);
            this.progress.upserted(rows);
        }
        return rows;
    }

    /**
     * Record and log a skipped repository.
     * @param name Repository name
     * @param type Repository type, may be null
     * @param reason Reason
     */
    private void skip(final String name, final String type, final String reason) {
        this.progress.repoSkipped();
        EcsLogger.info(IndexReindex.LOGGER)
            .message(
                String.format(
                    "Search index rebuild skipped repository (type %s): %s", type, reason
                )
            )
            .eventCategory("database")
            .eventAction("search_reindex_repo")
            .eventOutcome("unknown")
            .field("repository.name", name)
            .field("event.reason", reason)
            .field("log.source", "application")
            .log();
    }

    /**
     * Log the end of a run.
     * @param status Final status
     * @param error Fatal error, null if the run completed
     * @param millis Run duration
     */
    private void logFinished(final ReindexStatus status, final String error, final long millis) {
        final String summary = String.format(
            "%d/%d repositories (%d skipped, %d failed), %d rows pruned, %d upserted, %d removed",
            status.reposDone(), status.reposTotal(), status.reposSkipped(),
            status.reposFailed(), status.rowsPruned(), status.rowsUpserted(),
            status.rowsRemoved()
        );
        if (error == null && this.progress.failures() == 0) {
            EcsLogger.info(IndexReindex.LOGGER)
                .message("Search index rebuild finished: " + summary)
                .eventCategory("database")
                .eventAction("search_reindex_finish")
                .eventOutcome("success")
                .duration(millis)
                .field("log.source", "application")
                .log();
        } else {
            EcsLogger.error(IndexReindex.LOGGER)
                .message("Search index rebuild failed: " + summary)
                .eventCategory("database")
                .eventAction("search_reindex_finish")
                .eventOutcome("failure")
                .field("event.reason", error == null ? status.lastError() : error)
                .duration(millis)
                .field("log.source", "application")
                .log();
        }
    }

    /**
     * Human-readable error text.
     * @param error Error
     * @return Message
     */
    private static String describe(final Throwable error) {
        final String msg = error.getMessage();
        final String text;
        if (msg == null || msg.isBlank()) {
            text = error.getClass().getSimpleName();
        } else {
            text = msg;
        }
        return text;
    }
}
