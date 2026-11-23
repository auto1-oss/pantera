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
package com.auto1.pantera.asto.misc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsSame;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link BoundedFanOut}.
 *
 * @since 2.2.14
 */
final class BoundedFanOutTest {

    @Test
    @Timeout(30)
    void neverRunsMoreActionsThanItsWidth() throws Exception {
        final List<CompletableFuture<Void>> started = new CopyOnWriteArrayList<>();
        final CompletableFuture<Void> done = new BoundedFanOut<Integer>(
            3,
            item -> {
                final CompletableFuture<Void> gate = new CompletableFuture<>();
                started.add(gate);
                return gate;
            }
        ).run(IntStream.range(0, 10).boxed().collect(Collectors.toList()));
        MatcherAssert.assertThat(
            "three actions start, the others wait for a free slot",
            started.size(), new IsEqual<>(3)
        );
        started.get(1).complete(null);
        MatcherAssert.assertThat(
            "a finished action frees exactly one slot",
            started.size(), new IsEqual<>(4)
        );
        MatcherAssert.assertThat(
            "the run is not over while actions are pending",
            done.isDone(), new IsEqual<>(false)
        );
        for (int idx = 0; idx < started.size(); idx += 1) {
            started.get(idx).complete(null);
        }
        done.get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "every item was started once",
            started.size(), new IsEqual<>(10)
        );
    }

    @Test
    @Timeout(60)
    void runsManyImmediatelyCompletedActionsWithoutGrowingTheStack() throws Exception {
        final AtomicInteger count = new AtomicInteger();
        new BoundedFanOut<Integer>(
            4,
            item -> {
                count.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
        ).run(Collections.nCopies(500_000, 1)).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(count.get(), new IsEqual<>(500_000));
    }

    @Test
    @Timeout(30)
    void failsWithFirstFailureAndStartsNothingAfterIt() {
        final IllegalStateException boom = new IllegalStateException("boom");
        final List<Integer> started = new ArrayList<>(10);
        final CompletableFuture<Void> done = new BoundedFanOut<Integer>(
            1,
            item -> {
                started.add(item);
                final CompletableFuture<Void> res;
                if (item == 2) {
                    res = CompletableFuture.failedFuture(boom);
                } else {
                    res = CompletableFuture.completedFuture(null);
                }
                return res;
            }
        ).run(IntStream.range(0, 10).boxed().collect(Collectors.toList()));
        final ExecutionException err = Assertions.assertThrows(
            ExecutionException.class, () -> done.get(10, TimeUnit.SECONDS)
        );
        MatcherAssert.assertThat(
            "the failure of the action is the failure of the run",
            err.getCause(), new IsSame<>(boom)
        );
        MatcherAssert.assertThat(
            "no item is started after the failed one",
            started, new IsEqual<>(List.of(0, 1, 2))
        );
    }

    @Test
    @Timeout(30)
    void turnsThrowingActionIntoFailure() {
        final CompletableFuture<Void> done = new BoundedFanOut<Integer>(
            2,
            item -> {
                throw new IllegalStateException("thrown");
            }
        ).run(List.of(1, 2, 3));
        MatcherAssert.assertThat(
            done.isCompletedExceptionally(), new IsEqual<>(true)
        );
    }

    @Test
    @Timeout(30)
    void completesForNoItems() throws Exception {
        final AtomicInteger count = new AtomicInteger();
        new BoundedFanOut<Integer>(
            5,
            item -> {
                count.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
        ).run(Collections.emptyList()).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(count.get(), new IsEqual<>(0));
    }
}
