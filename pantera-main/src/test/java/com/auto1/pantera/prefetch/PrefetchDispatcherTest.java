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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PrefetchDispatcher}.
 *
 * <p>All collaborators are hand-rolled fakes — Mockito is not on the
 * {@code pantera-main} classpath. {@link RecordingSubmitter} captures
 * submitted tasks; {@link RecordingParser} records calls and may either
 * return a fixed list or throw, per-test.</p>
 *
 * @since 2.2.0
 */
class PrefetchDispatcherTest {

    private static final String REPO = "maven-central";
    private static final String REPO_TYPE = "maven-proxy";
    private static final String UPSTREAM = "https://repo1.maven.org/maven2";
    private static final String URL_PATH = "com/example/foo/1.0/foo-1.0.pom";

    @Test
    void noOpWhenGlobalKillSwitchOff() {
        final PrefetchTuning off = new PrefetchTuning(false, 64, 16, 2048, 8);
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
            submitter
        );

        dispatcher.onCacheWrite(event());

        MatcherAssert.assertThat("submitter must not see any submits when global flag is off",
            submitter.submitCount(), Matchers.equalTo(0));
        MatcherAssert.assertThat("parser must NOT be invoked when global flag is off",
            parser.parseCount(), Matchers.equalTo(0));
    }

    @Test
    void noOpWhenPerRepoFlagFalse() {
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
            submitter
        );

        dispatcher.onCacheWrite(event());

        MatcherAssert.assertThat(submitter.submitCount(), Matchers.equalTo(0));
        MatcherAssert.assertThat("parser must NOT be invoked when repo flag is false",
            parser.parseCount(), Matchers.equalTo(0));
    }

    @Test
    void noOpWhenNoParserRegisteredForRepoType() {
        final RecordingSubmitter submitter = new RecordingSubmitter();
        final PrefetchDispatcher dispatcher = new PrefetchDispatcher(
            PrefetchTuning::defaults,
            repo -> Boolean.TRUE,
            repo -> UPSTREAM,
            Map.of(),
            repo -> "file-proxy",
            submitter
        );

        Assertions.assertDoesNotThrow(() -> dispatcher.onCacheWrite(event()));

        MatcherAssert.assertThat(submitter.submitCount(), Matchers.equalTo(0));
    }

    @Test
    void submitsOneTaskPerParsedDependency() {
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
            submitter
        );

        dispatcher.onCacheWrite(event());

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
    void swallowsThrowableFromCallback() {
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
            submitter
        );

        // Hard contract from Task 11: a misbehaving callback must NEVER
        // bubble up to the cache-write path. We assert no throw.
        Assertions.assertDoesNotThrow(() -> dispatcher.onCacheWrite(event()));

        MatcherAssert.assertThat(parser.parseCount(), Matchers.equalTo(1));
        MatcherAssert.assertThat("no tasks submitted when parser failed",
            submitter.submitCount(), Matchers.equalTo(0));
    }

    // ============================================================
    //  Helpers
    // ============================================================

    private static CacheWriteEvent event() {
        return new CacheWriteEvent(REPO, URL_PATH, Paths.get("/tmp/fake.pom"), 42L, Instant.now());
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
