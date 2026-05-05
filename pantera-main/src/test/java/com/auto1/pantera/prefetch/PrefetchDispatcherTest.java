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
package com.auto1.pantera.prefetch;

import com.auto1.pantera.http.cache.CacheWriteEvent;
import com.auto1.pantera.prefetch.parser.PrefetchParser;
import com.auto1.pantera.settings.runtime.PrefetchTuning;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PrefetchDispatcher}.
 *
 * <p>All collaborators are hand-rolled fakes — Mockito is not on the
 * {@code pantera-main} classpath. {@link RecordingSubmitter} captures
 * submitted tasks; {@link RecordingParser} records calls and may either
 * return a fixed list or throw, per-test.</p>
 *
 * <p>Tests use a single-thread executor (or a deliberately-tight
 * bounded executor for queue-full scenarios) so the async dispatch
 * completes deterministically before assertions.</p>
 *
 * @since 2.2.0
 */
@SuppressWarnings({"PMD.AvoidUsingHardCodedIP", "PMD.TooManyMethods", "PMD.ExcessiveImports"})
class PrefetchDispatcherTest {

    private static final String REPO = "maven-central";
    private static final String REPO_TYPE = "maven-proxy";
    private static final String UPSTREAM = "https://repo1.maven.org/maven2";
    private static final String URL_PATH = "com/example/foo/1.0/foo-1.0.pom";

    private Path eventFile;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws IOException {
        this.eventFile = Files.createTempFile("pantera-prefetch-test-", ".bin");
        Files.writeString(this.eventFile, "fake pom bytes");
        this.executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (this.executor != null && !this.executor.isShutdown()) {
            this.executor.shutdown();
            try {
                this.executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        if (this.eventFile != null) {
            Files.deleteIfExists(this.eventFile);
        }
    }

    @Test
    void noOpWhenGlobalKillSwitchOff() throws Exception {
        final PrefetchTuning off = new PrefetchTuning(
            false, 64, 16,
            java.util.Map.of("maven", 16, "gradle", 16, "npm", 4),
            2048, 8
        );
        final RecordingSubmitter submitter = new RecordingSubmitter();
        final RecordingParser parser = new RecordingParser(
            List.of(Coordinate.maven("com.example", "bar", "1.0"))
        );
        final PrefetchDispatcher dispatcher = new PrefetchDispatcher(
            () -> off,
            repo -> Boolean.TRUE,
            repo -> UPSTREAM,
            Map.of(REPO_TYPE, parser),
            repo -> REPO_TYPE,
            submitter,
            this.executor
        );

        dispatcher.onCacheWrite(event());
        drain(this.executor);

        MatcherAssert.assertThat("submitter must not see any submits when global flag is off",
            submitter.submitCount(), Matchers.equalTo(0));
        MatcherAssert.assertThat("parser must NOT be invoked when global flag is off",
            parser.parseCount(), Matchers.equalTo(0));
    }

    @Test
    void noOpWhenPerRepoFlagFalse() throws Exception {
        final RecordingSubmitter submitter = new RecordingSubmitter();
        final RecordingParser parser = new RecordingParser(
            List.of(Coordinate.maven("com.example", "bar", "1.0"))
        );
        final PrefetchDispatcher dispatcher = new PrefetchDispatcher(
            PrefetchTuning::defaults,
            repo -> Boolean.FALSE,
            repo -> UPSTREAM,
            Map.of(REPO_TYPE, parser),
            repo -> REPO_TYPE,
            submitter,
            this.executor
        );

        dispatcher.onCacheWrite(event());
        drain(this.executor);

        MatcherAssert.assertThat(submitter.submitCount(), Matchers.equalTo(0));
        MatcherAssert.assertThat("parser must NOT be invoked when repo flag is false",
            parser.parseCount(), Matchers.equalTo(0));
    }

    @Test
    void noOpWhenNoParserRegisteredForRepoType() throws Exception {
        final RecordingSubmitter submitter = new RecordingSubmitter();
        final PrefetchDispatcher dispatcher = new PrefetchDispatcher(
            PrefetchTuning::defaults,
            repo -> Boolean.TRUE,
            repo -> UPSTREAM,
            Map.of(),
            repo -> "file-proxy",
            submitter,
            this.executor
        );

        Assertions.assertDoesNotThrow(() -> dispatcher.onCacheWrite(event()));
        drain(this.executor);

        MatcherAssert.assertThat(submitter.submitCount(), Matchers.equalTo(0));
    }

    @Test
    void submitsOneTaskPerParsedDependency() throws Exception {
        final List<Coordinate> three = List.of(
            Coordinate.maven("com.example", "a", "1.0"),
            Coordinate.maven("com.example", "b", "2.0"),
            Coordinate.maven("com.example", "c", "3.0")
        );
        final RecordingSubmitter submitter = new RecordingSubmitter();
        final RecordingParser parser = new RecordingParser(three);
        final PrefetchDispatcher dispatcher = new PrefetchDispatcher(
            PrefetchTuning::defaults,
            repo -> Boolean.TRUE,
            repo -> UPSTREAM,
            Map.of(REPO_TYPE, parser),
            repo -> REPO_TYPE,
            submitter,
            this.executor
        );

        dispatcher.onCacheWrite(event());
        drain(this.executor);

        MatcherAssert.assertThat(parser.parseCount(), Matchers.equalTo(1));
        MatcherAssert.assertThat(submitter.submitCount(), Matchers.equalTo(3));
        MatcherAssert.assertThat(
            submitter.submittedTasks().stream().map(t -> t.coord().name()).toList(),
            Matchers.contains("a", "b", "c")
        );
        MatcherAssert.assertThat(
            "every submitted task carries the originating repo metadata",
            submitter.submittedTasks().stream().allMatch(
                t -> t.repoName().equals(REPO)
                    && t.repoType().equals(REPO_TYPE)
                    && t.upstreamUrl().equals(UPSTREAM)
            ),
            Matchers.is(true)
        );
    }

    @Test
    void swallowsThrowableFromCallback() throws Exception {
        final RecordingSubmitter submitter = new RecordingSubmitter();
        final RecordingParser parser = new RecordingParser(null) {
            @Override
            public List<Coordinate> parse(final Path bytesOnDisk) {
                super.parse(bytesOnDisk);
                throw new RuntimeException("kaboom from parser");
            }
        };
        final PrefetchDispatcher dispatcher = new PrefetchDispatcher(
            PrefetchTuning::defaults,
            repo -> Boolean.TRUE,
            repo -> UPSTREAM,
            Map.of(REPO_TYPE, parser),
            repo -> REPO_TYPE,
            submitter,
            this.executor
        );

        // Hard contract from Task 11: a misbehaving callback must NEVER
        // bubble up to the cache-write path. We assert no throw.
        Assertions.assertDoesNotThrow(() -> dispatcher.onCacheWrite(event()));
        drain(this.executor);

        MatcherAssert.assertThat(parser.parseCount(), Matchers.equalTo(1));
        MatcherAssert.assertThat("no tasks submitted when parser failed",
            submitter.submitCount(), Matchers.equalTo(0));
    }

    // ============================================================
    //  Phase 10 (async dispatch) tests
    // ============================================================

    /**
     * Verifies the cache-write hot path returns immediately even when the
     * parser is artificially slow. With sync dispatch, this test would
     * block ~100ms; with async dispatch the hot path completes in ms.
     */
    @Test
    void onCacheWrite_returnsImmediately_evenWhenParserIsSlow() throws Exception {
        final long parserSleepMs = 200L;
        final RecordingSubmitter submitter = new RecordingSubmitter();
        final RecordingParser slow = new RecordingParser(
            List.of(Coordinate.maven("com.example", "x", "1.0"))
        ) {
            @Override
            public List<Coordinate> parse(final Path bytesOnDisk) {
                try {
                    Thread.sleep(parserSleepMs);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return super.parse(bytesOnDisk);
            }
        };
        final PrefetchDispatcher dispatcher = new PrefetchDispatcher(
            PrefetchTuning::defaults,
            repo -> Boolean.TRUE,
            repo -> UPSTREAM,
            Map.of(REPO_TYPE, slow),
            repo -> REPO_TYPE,
            submitter,
            this.executor
        );

        final long t0 = System.nanoTime();
        dispatcher.onCacheWrite(event());
        final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        MatcherAssert.assertThat(
            "hot path must return well before the parser sleep elapses (sync would be >= "
                + parserSleepMs + "ms)",
            elapsedMs, Matchers.lessThan(parserSleepMs / 2)
        );
        // Drain the executor so the slow parse completes before tearDown
        // shuts the pool down.
        drain(this.executor);
        MatcherAssert.assertThat("parser eventually invoked off-thread",
            slow.parseCount(), Matchers.equalTo(1));
        MatcherAssert.assertThat("submit eventually fires off-thread",
            submitter.submitCount(), Matchers.equalTo(1));
    }

    /**
     * Saturates a 1-thread executor with a 1-slot queue and a parser that
     * blocks on a latch — additional submits should reject and increment
     * the dropped-events counter.
     */
    @Test
    void onCacheWrite_dropsWhenQueueFull() throws Exception {
        final ThreadPoolExecutor tight = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            new ThreadPoolExecutor.AbortPolicy()
        );
        final CountDownLatch holdParser = new CountDownLatch(1);
        final RecordingSubmitter submitter = new RecordingSubmitter();
        final RecordingParser blocking = new RecordingParser(List.of()) {
            @Override
            public List<Coordinate> parse(final Path bytesOnDisk) {
                try {
                    holdParser.await(2, TimeUnit.SECONDS);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return super.parse(bytesOnDisk);
            }
        };
        final PrefetchDispatcher dispatcher = new PrefetchDispatcher(
            PrefetchTuning::defaults,
            repo -> Boolean.TRUE,
            repo -> UPSTREAM,
            Map.of(REPO_TYPE, blocking),
            repo -> REPO_TYPE,
            submitter,
            tight
        );

        try {
            // Burst: 1 occupies the worker, 1 fills the queue, the rest reject.
            for (int i = 0; i < 6; i++) {
                dispatcher.onCacheWrite(event());
            }

            MatcherAssert.assertThat(
                "queue-full burst must increment droppedEventsTotal",
                dispatcher.droppedEventsTotal(), Matchers.greaterThanOrEqualTo(1L)
            );
        } finally {
            holdParser.countDown();
            tight.shutdown();
            tight.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    /**
     * stop() must drain in-flight async dispatches before returning.
     */
    @Test
    void stop_drainsInflightDispatches() throws Exception {
        // Use this dispatcher's own executor (not the field one) so we
        // can shut it down explicitly via dispatcher.stop().
        final ExecutorService ownExec = Executors.newFixedThreadPool(2);
        final RecordingSubmitter submitter = new RecordingSubmitter();
        final long parserSleepMs = 50L;
        final RecordingParser slow = new RecordingParser(
            List.of(Coordinate.maven("com.example", "y", "1.0"))
        ) {
            @Override
            public List<Coordinate> parse(final Path bytesOnDisk) {
                try {
                    Thread.sleep(parserSleepMs);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return super.parse(bytesOnDisk);
            }
        };
        final PrefetchDispatcher dispatcher = new PrefetchDispatcher(
            PrefetchTuning::defaults,
            repo -> Boolean.TRUE,
            repo -> UPSTREAM,
            Map.of(REPO_TYPE, slow),
            repo -> REPO_TYPE,
            submitter,
            ownExec
        );

        for (int i = 0; i < 5; i++) {
            dispatcher.onCacheWrite(event());
        }
        dispatcher.stop();

        MatcherAssert.assertThat("all 5 dispatches must have run before stop returned",
            slow.parseCount(), Matchers.equalTo(5));
        MatcherAssert.assertThat("all 5 submits must have fired before stop returned",
            submitter.submitCount(), Matchers.equalTo(5));
    }

    // ============================================================
    //  Helpers
    // ============================================================

    private CacheWriteEvent event() {
        return new CacheWriteEvent(REPO, URL_PATH, this.eventFile, 42L, Instant.now());
    }

    /** Drain the executor so async work finishes before assertions. */
    private static void drain(final ExecutorService exec) throws InterruptedException {
        exec.shutdown();
        if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Executor did not drain in time");
        }
    }

    /**
     * Hand-rolled stand-in for the coordinator's {@code submit} sink.
     * Records every {@link PrefetchTask} the dispatcher submits.
     */
    private static final class RecordingSubmitter implements java.util.function.Consumer<PrefetchTask> {
        private final List<PrefetchTask> tasks = new CopyOnWriteArrayList<>();
        private final AtomicInteger count = new AtomicInteger(0);

        @Override
        public void accept(final PrefetchTask task) {
            this.tasks.add(task);
            this.count.incrementAndGet();
        }

        int submitCount() {
            return this.count.get();
        }

        List<PrefetchTask> submittedTasks() {
            return List.copyOf(this.tasks);
        }
    }

    /**
     * Hand-rolled parser fake — returns a fixed {@link List} of
     * coordinates and counts invocations.
     */
    private static class RecordingParser implements PrefetchParser {
        private final List<Coordinate> result;
        private final AtomicInteger count = new AtomicInteger(0);

        RecordingParser(final List<Coordinate> result) {
            this.result = result;
        }

        @Override
        public List<Coordinate> parse(final Path bytesOnDisk) {
            this.count.incrementAndGet();
            return this.result;
        }

        int parseCount() {
            return this.count.get();
        }
    }
}
