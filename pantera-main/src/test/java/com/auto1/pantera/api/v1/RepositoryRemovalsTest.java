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

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link RepositoryRemovals}.
 *
 * @since 2.2.9
 */
final class RepositoryRemovalsTest {

    @Test
    void secondRemovalOfTheSameNameIsNotStarted() {
        final RepositoryRemovals removals = new RepositoryRemovals(Duration.ofMillis(10));
        final CompletableFuture<Void> parked = new CompletableFuture<>();
        final AtomicInteger started = new AtomicInteger();
        final Optional<CompletableFuture<Void>> first = removals.start(
            "maven", () -> {
                started.incrementAndGet();
                return parked;
            }
        );
        final Optional<CompletableFuture<Void>> second = removals.start(
            "maven", () -> {
                started.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
        );
        MatcherAssert.assertThat("First removal starts", first.isPresent(), new IsEqual<>(true));
        MatcherAssert.assertThat("Second removal is refused", second.isPresent(), new IsEqual<>(false));
        MatcherAssert.assertThat("Name is in progress", removals.inProgress("maven"), new IsEqual<>(true));
        MatcherAssert.assertThat("Only one pipeline ran", started.get(), new IsEqual<>(1));
    }

    @Test
    void nameIsReleasedWhenTheRemovalEnds() {
        final RepositoryRemovals removals = new RepositoryRemovals(Duration.ofMillis(10));
        final CompletableFuture<Void> parked = new CompletableFuture<>();
        removals.start("npm", () -> parked);
        parked.completeExceptionally(new IllegalStateException("boom"));
        MatcherAssert.assertThat(
            "A failed removal can be retried",
            removals.start("npm", () -> CompletableFuture.completedFuture(null)).isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void otherNamesAreIndependent() {
        final RepositoryRemovals removals = new RepositoryRemovals(Duration.ofMillis(10));
        removals.start("a", CompletableFuture::new);
        MatcherAssert.assertThat(
            removals.inProgress("b"),
            new IsEqual<>(false)
        );
    }

    @Test
    @Timeout(10)
    void longRemovalIsAnsweredAsPending() throws Exception {
        final RepositoryRemovals removals = new RepositoryRemovals(Duration.ofMillis(20));
        final CompletableFuture<Void> parked = new CompletableFuture<>();
        final RepositoryRemovals.Outcome outcome = removals.answer(parked)
            .get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "Pending while the data removal still runs",
            outcome.pending(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "The removal itself is not cancelled",
            parked.isDone(),
            new IsEqual<>(false)
        );
    }

    @Test
    @Timeout(10)
    void finishedRemovalIsAnsweredWithItsResult() throws Exception {
        final RepositoryRemovals removals = new RepositoryRemovals(Duration.ofSeconds(30));
        final RepositoryRemovals.Outcome done = removals.answer(
            CompletableFuture.completedFuture(null)
        ).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "Completed removal is not pending",
            done.pending(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "Completed removal has no failure",
            done.failure().isPresent(),
            new IsEqual<>(false)
        );
        final RepositoryRemovals.Outcome failed = removals.answer(
            CompletableFuture.failedFuture(new IllegalStateException("s3 down"))
        ).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "Failed removal carries its root cause",
            failed.failure().orElseThrow(),
            new IsInstanceOf(IllegalStateException.class)
        );
    }
}
