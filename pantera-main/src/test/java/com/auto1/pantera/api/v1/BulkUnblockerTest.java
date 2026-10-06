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
package com.auto1.pantera.api.v1;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BulkUnblocker}: per-item authorization, failure
 * isolation, auditing and normalisation, without Vert.x.
 */
final class BulkUnblockerTest {

    private static final Executor DIRECT = Runnable::run;

    @Test
    void forbiddenItemIsReportedAndNeverReleased() {
        final RecordingRelease release = new RecordingRelease();
        final RecordingAudit audit = new RecordingAudit();
        final BulkUnblocker.Outcome out = new BulkUnblocker(
            repo -> "npm-proxy", repo -> false, release, audit, DIRECT
        ).run(List.of(new BulkUnblockRequest.Item("npm-proxy", "lodash", "1.0.0")), "ayd").join();
        MatcherAssert.assertThat("nothing unblocked", out.unblocked().size(), new IsEqual<>(0));
        MatcherAssert.assertThat(
            "reason", out.failed().getJsonObject(0).getString("reason"), new IsEqual<>("forbidden")
        );
        MatcherAssert.assertThat("release untouched", release.calls.size(), new IsEqual<>(0));
        MatcherAssert.assertThat("audited as failure", audit.success.get(0), new IsEqual<>(false));
        MatcherAssert.assertThat(
            "bulk marker", audit.details.get(0).get("bulk"), new IsEqual<>(Boolean.TRUE)
        );
    }

    @Test
    void lookupErrorFailsOnlyThatItemAndTheNextOneStillRuns() {
        final RecordingRelease release = new RecordingRelease();
        final BulkUnblocker.Outcome out = new BulkUnblocker(
            repo -> {
                if ("broken".equals(repo)) {
                    throw new IllegalStateException("Failed to get repo: broken");
                }
                return "npm-proxy";
            },
            repo -> true, release, new RecordingAudit(), DIRECT
        ).run(
            List.of(
                new BulkUnblockRequest.Item("broken", "a", "1"),
                new BulkUnblockRequest.Item("npm-proxy", "b", "2")
            ), "ayd"
        ).join();
        MatcherAssert.assertThat("second item released", release.calls.size(), new IsEqual<>(1));
        MatcherAssert.assertThat("one failure", out.failed().size(), new IsEqual<>(1));
        MatcherAssert.assertThat(
            "failure names the cause",
            out.failed().getJsonObject(0).getString("reason"),
            new IsEqual<>("Failed to get repo: broken")
        );
        MatcherAssert.assertThat("one success", out.unblocked().size(), new IsEqual<>(1));
    }

    @Test
    void unknownRepositoryAndFailedReleaseAreReportedPerItem() {
        final RecordingRelease release = new RecordingRelease();
        release.failNext = true;
        final BulkUnblocker.Outcome out = new BulkUnblocker(
            repo -> {
                if ("gone".equals(repo)) {
                    throw new IllegalArgumentException("Repository 'gone' not found");
                }
                return "npm-proxy";
            },
            repo -> true, release, new RecordingAudit(), DIRECT
        ).run(
            List.of(
                new BulkUnblockRequest.Item("gone", "a", "1"),
                new BulkUnblockRequest.Item("npm-proxy", "b", "2")
            ), "ayd"
        ).join();
        MatcherAssert.assertThat("both failed", out.failed().size(), new IsEqual<>(2));
        MatcherAssert.assertThat(
            "unknown repo reason",
            out.failed().getJsonObject(0).getString("reason"),
            new IsEqual<>("Repository 'gone' not found")
        );
        MatcherAssert.assertThat(
            "release failure reason",
            out.failed().getJsonObject(1).getString("reason"), new IsEqual<>("db down")
        );
    }

    @Test
    void successIsAuditedWithNormalisedMavenNameAndDuplicatesCollapse() {
        final RecordingRelease release = new RecordingRelease();
        final RecordingAudit audit = new RecordingAudit();
        final BulkUnblocker.Outcome out = new BulkUnblocker(
            repo -> "maven-proxy", repo -> true, release, audit, DIRECT
        ).run(
            List.of(
                new BulkUnblockRequest.Item("maven-central", "com.example:lib", "1.0.0"),
                new BulkUnblockRequest.Item("maven-central", "com.example.lib", "1.0.0")
            ), "ayd"
        ).join();
        MatcherAssert.assertThat("released once", release.calls.size(), new IsEqual<>(1));
        MatcherAssert.assertThat(
            "normalised name reaches the service",
            release.calls.get(0), new IsEqual<>("maven-central|maven-proxy|com.example.lib|1.0.0")
        );
        MatcherAssert.assertThat("reported once", out.unblocked().size(), new IsEqual<>(1));
        MatcherAssert.assertThat(
            "echoes the caller's spelling",
            out.unblocked().getJsonObject(0).getString("artifact"), new IsEqual<>("com.example:lib")
        );
        MatcherAssert.assertThat("audit success", audit.success.get(0), new IsEqual<>(true));
        MatcherAssert.assertThat(
            "audit carries repo type", audit.details.get(0).get("repository.type"),
            new IsEqual<>("maven-proxy")
        );
    }

    @Test
    void rejectedExecutorReportsTheRemainingItemsAsFailed() {
        final AtomicInteger admitted = new AtomicInteger();
        final Executor flaky = task -> {
            if (admitted.getAndIncrement() == 0) {
                task.run();
            } else {
                throw new RejectedExecutionException("queue full");
            }
        };
        final BulkUnblocker.Outcome out = new BulkUnblocker(
            repo -> "npm-proxy", repo -> true, new RecordingRelease(), new RecordingAudit(), flaky
        ).run(
            List.of(
                new BulkUnblockRequest.Item("npm-proxy", "a", "1"),
                new BulkUnblockRequest.Item("npm-proxy", "b", "2")
            ), "ayd"
        ).join();
        MatcherAssert.assertThat("first ran", out.unblocked().size(), new IsEqual<>(1));
        MatcherAssert.assertThat("second reported", out.failed().size(), new IsEqual<>(1));
        MatcherAssert.assertThat(
            "with the rejection reason",
            out.failed().getJsonObject(0).getString("reason"), new IsEqual<>("queue full")
        );
    }

    /**
     * Release fake recording {@code repo|type|artifact|version}.
     */
    private static final class RecordingRelease implements BulkUnblocker.Release {
        private final List<String> calls = new ArrayList<>();
        private boolean failNext;

        @Override
        public CompletableFuture<Void> apply(final String repo, final String type,
            final String artifact, final String version) {
            this.calls.add(String.join("|", repo, type, artifact, version));
            if (this.failNext) {
                this.failNext = false;
                return CompletableFuture.failedFuture(new IllegalStateException("db down"));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Audit fake.
     */
    private static final class RecordingAudit implements BulkUnblocker.Audit {
        private final List<Map<String, Object>> details = new ArrayList<>();
        private final List<Boolean> success = new ArrayList<>();

        @Override
        public void record(final String repo, final Map<String, Object> details,
            final boolean success) {
            this.details.add(details);
            this.success.add(success);
        }
    }
}
