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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.IsNull;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link IndexReindex} against an in-memory {@link ReindexStore}.
 *
 * @since 2.2.9
 */
@Timeout(30)
final class IndexReindexTest {

    @Test
    void prunesRowsOfDeletedRepositoriesInBatches() throws Exception {
        final FakeStore store = new FakeStore();
        store.row("qa_1", "a", "1", 1L);
        store.row("qa_1", "b", "1", 1L);
        store.row("qa_1", "c", "1", 1L);
        store.row("qa_2", "a", "1", 1L);
        store.row("live", "a", "1", 1L);
        final FakeRepos repos = new FakeRepos()
            .with("live", new ReindexRepos.Target("maven", "storage is not on the local file system"));
        final ReindexStatus status = IndexReindexTest.runOnce(store, repos, IndexReindexTest.noScanner());
        MatcherAssert.assertThat(
            "Rows of deleted repositories are gone, the live repository is untouched",
            store.repos(), new IsEqual<>(Set.of("live"))
        );
        MatcherAssert.assertThat(
            "Pruned rows are counted", status.rowsPruned(), new IsEqual<>(4L)
        );
        MatcherAssert.assertThat(
            "Prune deletes in bounded batches", store.maxPruneLimit, new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "Run is finished", status.running(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "Finish time is recorded", status.finishedAt(), new IsNot<>(new IsNull<>())
        );
    }

    @Test
    void neverPrunesWhenNoRepositoryIsConfigured() throws Exception {
        final FakeStore store = new FakeStore();
        store.row("qa_1", "a", "1", 1L);
        IndexReindexTest.runOnce(store, new FakeRepos(), IndexReindexTest.noScanner());
        MatcherAssert.assertThat(store.repos(), new IsEqual<>(Set.of("qa_1")));
    }

    @Test
    void keepsRowsOfRepositoryRecreatedMeanwhile() throws Exception {
        final FakeStore store = new FakeStore();
        store.row("late", "a", "1", 1L);
        final FakeRepos repos = new FakeRepos()
            .with("other", new ReindexRepos.Target("maven", "skip"));
        // listed before it existed, exists by the time it is pruned
        repos.hidden.add("late");
        IndexReindexTest.runOnce(store, repos, IndexReindexTest.noScanner());
        MatcherAssert.assertThat(store.repos(), new IsEqual<>(Set.of("late")));
    }

    @Test
    void reconcilesRepositoryWithTheBackfillScanner(@TempDir final Path root)
        throws Exception {
        Files.createDirectories(root.resolve("lib/1.0"));
        Files.writeString(root.resolve("lib/1.0/a.txt"), "abc");
        Files.writeString(root.resolve("lib/1.0/b.txt"), "abcdef");
        final FakeStore store = new FakeStore();
        store.row("files", "lib.1.0.a.txt", "1.0", 1L);
        store.row("files", "gone.txt", "UNKNOWN", 1L);
        final FakeRepos repos = new FakeRepos()
            .with("files", new ReindexRepos.Target("file", root));
        final ReindexStatus status = IndexReindexTest.runOnce(
            store, repos, ScannerFactory::create
        );
        MatcherAssert.assertThat(
            "Index holds exactly what storage holds",
            store.keys("files"),
            new IsEqual<>(Set.of("lib.1.0.a.txt@1.0", "lib.1.0.b.txt@1.0"))
        );
        MatcherAssert.assertThat(
            "Stale row is counted as removed", status.rowsRemoved(), new IsEqual<>(1L)
        );
        MatcherAssert.assertThat(
            "Scanned rows are counted as upserted", status.rowsUpserted(), new IsEqual<>(2L)
        );
        MatcherAssert.assertThat(
            "Repository is done", status.reposDone(), new IsEqual<>(1)
        );
    }

    @Test
    void neverRemovesRowsWrittenDuringTheScan(@TempDir final Path root) throws Exception {
        final FakeStore store = new FakeStore();
        final FakeRepos repos = new FakeRepos()
            .with("files", new ReindexRepos.Target("file", root));
        final Scanner scanner = (dir, name) -> {
            // a live upload lands while the storage is being walked
            store.row("files", "fresh.txt", "UNKNOWN", System.currentTimeMillis() + 1L);
            return Stream.empty();
        };
        IndexReindexTest.runOnce(store, repos, type -> scanner);
        MatcherAssert.assertThat(store.keys("files"), new IsEqual<>(Set.of("fresh.txt@UNKNOWN")));
    }

    @Test
    void upsertsSortedBoundedBatches(@TempDir final Path root) throws Exception {
        final FakeStore store = new FakeStore();
        final FakeRepos repos = new FakeRepos()
            .with("npm", new ReindexRepos.Target("npm", root));
        final Scanner scanner = (dir, name) -> Stream.of("e", "c", "a", "d", "b")
            .map(n -> IndexReindexTest.record(name, n));
        IndexReindexTest.runOnce(store, repos, type -> scanner);
        MatcherAssert.assertThat(
            store.batches,
            new IsEqual<>(List.of(List.of("c", "e"), List.of("a", "d"), List.of("b")))
        );
    }

    @Test
    void skipsRepositoriesItCannotScanAndLeavesTheirRows(@TempDir final Path root)
        throws Exception {
        final FakeStore store = new FakeStore();
        store.row("conan", "pkg", "1", 1L);
        store.row("s3", "pkg", "1", 1L);
        store.row("group", "pkg", "1", 1L);
        final FakeRepos repos = new FakeRepos()
            .with("conan", new ReindexRepos.Target("conan", root))
            .with("s3", new ReindexRepos.Target("maven", "storage is not on the local file system"))
            .with("group", new ReindexRepos.Target("maven-group", "group"));
        final ReindexStatus status = IndexReindexTest.runOnce(
            store, repos, ScannerFactory::create
        );
        MatcherAssert.assertThat(
            "Rows of skipped repositories are untouched",
            store.repos(), new IsEqual<>(Set.of("conan", "s3", "group"))
        );
        MatcherAssert.assertThat(
            "Skips are counted", status.reposSkipped(), new IsEqual<>(3)
        );
        MatcherAssert.assertThat(
            "Skipped repositories count as done", status.reposDone(), new IsEqual<>(3)
        );
    }

    @Test
    void refusesSecondRunWhileOneIsRunning(@TempDir final Path root) throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Scanner parked = (dir, name) -> {
            entered.countDown();
            IndexReindexTest.await(release);
            return Stream.empty();
        };
        final ExecutorService exec = Executors.newSingleThreadExecutor();
        final IndexReindex job = new IndexReindex(
            new FakeStore(),
            new FakeRepos().with("r", new ReindexRepos.Target("file", root)),
            type -> parked, exec, 2
        );
        MatcherAssert.assertThat("First run starts", job.start("admin"), new IsEqual<>(true));
        entered.await();
        MatcherAssert.assertThat("Status is running", job.status().state(), new IsEqual<>("running"));
        MatcherAssert.assertThat("Second run is refused", job.start("admin"), new IsEqual<>(false));
        release.countDown();
        // the single rebuild thread runs this only after the first run ended
        exec.submit(() -> { }).get();
        MatcherAssert.assertThat("Back to idle", job.status().state(), new IsEqual<>("idle"));
        MatcherAssert.assertThat("A new run can start", job.start("admin"), new IsEqual<>(true));
        exec.shutdown();
        exec.awaitTermination(20, TimeUnit.SECONDS);
    }

    @Test
    void reportsRunHeldByAnotherNode() throws Exception {
        final FakeStore store = new FakeStore();
        store.locked = true;
        store.row("qa_1", "a", "1", 1L);
        final ReindexStatus status = IndexReindexTest.runOnce(
            store, new FakeRepos().with("r", new ReindexRepos.Target("maven", "skip")),
            IndexReindexTest.noScanner()
        );
        MatcherAssert.assertThat(
            "Nothing is pruned", store.repos(), new IsEqual<>(Set.of("qa_1"))
        );
        MatcherAssert.assertThat(
            "Error names the other node",
            status.lastError(), new StringContains("another node")
        );
    }

    @Test
    void continuesAfterRepositoryFailure(@TempDir final Path root) throws Exception {
        final FakeStore store = new FakeStore();
        final FakeRepos repos = new FakeRepos()
            .with("a-broken", new ReindexRepos.Target("npm", root))
            .with("b-fine", new ReindexRepos.Target("npm", root));
        final Scanner scanner = (dir, name) -> {
            if ("a-broken".equals(name)) {
                throw new IOException("disk on fire");
            }
            return Stream.of(IndexReindexTest.record(name, "x"));
        };
        final ReindexStatus status = IndexReindexTest.runOnce(store, repos, type -> scanner);
        MatcherAssert.assertThat(
            "Later repository is still rebuilt", store.keys("b-fine"), new IsEqual<>(Set.of("x@1"))
        );
        MatcherAssert.assertThat("Failure is counted", status.reposFailed(), new IsEqual<>(1));
        MatcherAssert.assertThat(
            "Failure is reported", status.lastError(), new StringContains("a-broken")
        );
    }

    /**
     * Run once to completion with batch size 2.
     * @param store Store
     * @param repos Repos
     * @param scanners Scanners
     * @return Final status
     * @throws InterruptedException If interrupted
     */
    private static ReindexStatus runOnce(
        final FakeStore store, final FakeRepos repos, final Function<String, Scanner> scanners
    ) throws InterruptedException {
        final ExecutorService exec = Executors.newSingleThreadExecutor();
        final IndexReindex job = new IndexReindex(store, repos, scanners, exec, 2);
        MatcherAssert.assertThat("Run starts", job.start("admin"), new IsEqual<>(true));
        exec.shutdown();
        exec.awaitTermination(20, TimeUnit.SECONDS);
        return job.status();
    }

    /**
     * Scanner lookup that knows no type.
     * @return Lookup
     */
    private static Function<String, Scanner> noScanner() {
        return type -> {
            throw new IllegalArgumentException(type);
        };
    }

    /**
     * Artifact record.
     * @param repo Repository
     * @param name Name
     * @return Record
     */
    private static ArtifactRecord record(final String repo, final String name) {
        return new ArtifactRecord("npm", repo, name, "1", 1L, 1L, null, "system", null);
    }

    /**
     * Await a latch without checked exceptions.
     * @param latch Latch
     */
    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * In-memory index table.
     * @since 2.2.9
     */
    private static final class FakeStore implements ReindexStore {

        /**
         * Rows: repo -> key -> (id, created).
         */
        private final Map<String, Map<String, long[]>> rows = new HashMap<>();

        /**
         * Name lists of the upsert batches, in call order.
         */
        private final List<List<String>> batches = new ArrayList<>();

        /**
         * Largest prune limit seen.
         */
        private int maxPruneLimit;

        /**
         * Whether another node holds the lock.
         */
        private boolean locked;

        /**
         * Id sequence.
         */
        private long ids;

        synchronized void row(final String repo, final String name, final String version,
            final long created) {
            this.ids += 1L;
            this.rows.computeIfAbsent(repo, key -> new HashMap<>())
                .put(name + "@" + version, new long[] {this.ids, created});
        }

        synchronized Set<String> repos() {
            return Set.copyOf(this.rows.keySet());
        }

        synchronized Set<String> keys(final String repo) {
            return Set.copyOf(this.rows.getOrDefault(repo, Map.of()).keySet());
        }

        @Override
        public java.util.Optional<Lease> lock() {
            final java.util.Optional<Lease> res;
            if (this.locked) {
                res = java.util.Optional.empty();
            } else {
                res = java.util.Optional.of(() -> { });
            }
            return res;
        }

        @Override
        public synchronized List<String> indexedRepos() {
            return new ArrayList<>(this.rows.keySet());
        }

        @Override
        public synchronized long pruneBatch(final String repo, final int limit) {
            this.maxPruneLimit = Math.max(this.maxPruneLimit, limit);
            final Map<String, long[]> repoRows = this.rows.getOrDefault(repo, new HashMap<>());
            final List<String> doomed = repoRows.keySet().stream().sorted().limit(limit).toList();
            doomed.forEach(repoRows::remove);
            if (repoRows.isEmpty()) {
                this.rows.remove(repo);
            }
            return doomed.size();
        }

        @Override
        public synchronized Session open(final String repo, final long started) {
            final long max = this.ids;
            final Set<String> seen = new java.util.HashSet<>();
            final FakeStore self = this;
            return new Session() {
                @Override
                public long upsert(final List<ArtifactRecord> batch) {
                    synchronized (self) {
                        self.batches.add(batch.stream().map(ArtifactRecord::name).toList());
                        long count = 0L;
                        for (final ArtifactRecord rec : batch) {
                            final String key = rec.name() + "@" + rec.version();
                            seen.add(key);
                            if (!self.keys(repo).contains(key)) {
                                self.row(repo, rec.name(), rec.version(), rec.createdDate());
                            }
                            count += 1L;
                        }
                        return count;
                    }
                }

                @Override
                public long removeUnseen(final int limit) {
                    synchronized (self) {
                        final Map<String, long[]> repoRows =
                            self.rows.getOrDefault(repo, new HashMap<>());
                        final List<String> doomed = repoRows.entrySet().stream()
                            .filter(e -> e.getValue()[0] <= max && e.getValue()[1] < started)
                            .map(Map.Entry::getKey)
                            .filter(key -> !seen.contains(key))
                            .sorted().limit(limit).toList();
                        doomed.forEach(repoRows::remove);
                        return doomed.size();
                    }
                }

                @Override
                public void close() {
                    seen.clear();
                }
            };
        }
    }

    /**
     * Configured repositories.
     * @since 2.2.9
     */
    private static final class FakeRepos implements ReindexRepos {

        /**
         * Targets by name.
         */
        private final Map<String, Target> targets = new HashMap<>();

        /**
         * Repositories that exist but are not listed.
         */
        private final Set<String> hidden = new java.util.HashSet<>();

        FakeRepos with(final String name, final Target target) {
            this.targets.put(name, target);
            return this;
        }

        @Override
        public Collection<String> names() {
            return List.copyOf(this.targets.keySet());
        }

        @Override
        public boolean exists(final String name) {
            return this.targets.containsKey(name) || this.hidden.contains(name);
        }

        @Override
        public Target target(final String name) {
            return this.targets.get(name);
        }
    }
}
