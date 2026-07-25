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
package com.auto1.pantera.scheduling;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.awaitility.Awaitility;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link LocalEventDrainScheduler} — the per-node replacement
 * for the pre-2.3.0 clustered-Quartz {@code EventsProcessor} drain (WS2.2).
 * No Docker/DB required: pure in-memory queue + consumer, matching the
 * project's doctrine of proving semantics via invocation counts / latches
 * rather than wall-clock bounds.
 * @since 2.3.0
 */
final class LocalEventDrainSchedulerTest {

    /**
     * Scheduler under test, closed after each test.
     */
    private LocalEventDrainScheduler<String> scheduler;

    @AfterEach
    void tearDown() {
        if (this.scheduler != null) {
            this.scheduler.close();
        }
    }

    @Test
    @Timeout(10)
    void drainsAllQueuedItemsAcrossMultipleWorkers() {
        final Queue<String> queue = new ConcurrentLinkedDeque<>();
        final CountingConsumer first = new CountingConsumer();
        final CountingConsumer second = new CountingConsumer();
        for (int idx = 0; idx < 26; idx++) {
            queue.add("item-" + idx);
        }
        this.scheduler = new LocalEventDrainScheduler<>(
            queue, List.of(first, second), 1
        );
        Awaitility.await().atMost(10, TimeUnit.SECONDS)
            .until(() -> first.count.get() + second.count.get() == 26);
        MatcherAssert.assertThat(
            "Queue must be fully drained",
            queue.isEmpty(),
            Matchers.is(true)
        );
    }

    @Test
    @Timeout(10)
    void retriesOnEventProcessingErrorThenSucceeds() {
        final Queue<String> queue = new ConcurrentLinkedDeque<>();
        queue.add("flaky");
        final AtomicInteger attempts = new AtomicInteger();
        final Consumer<String> flaky = item -> {
            if (attempts.incrementAndGet() < 2) {
                throw new EventProcessingError("transient", null);
            }
        };
        this.scheduler = new LocalEventDrainScheduler<>(queue, List.of(flaky), 1);
        Awaitility.await().atMost(10, TimeUnit.SECONDS)
            .until(() -> attempts.get() == 2);
        MatcherAssert.assertThat(
            "Item must be consumed exactly once it stops throwing",
            queue.isEmpty(),
            Matchers.is(true)
        );
    }

    @Test
    @Timeout(10)
    void dropsItemAfterMaxRetriesAndKeepsDraining() {
        final Queue<String> queue = new ConcurrentLinkedDeque<>();
        queue.add("always-fails");
        queue.add("healthy");
        final AtomicInteger failingAttempts = new AtomicInteger();
        final List<String> processed = new CopyOnWriteArrayList<>();
        final Consumer<String> action = item -> {
            if ("always-fails".equals(item)) {
                failingAttempts.incrementAndGet();
                throw new EventProcessingError("permanent", null);
            }
            processed.add(item);
        };
        this.scheduler = new LocalEventDrainScheduler<>(queue, List.of(action), 1);
        Awaitility.await().atMost(10, TimeUnit.SECONDS)
            .until(() -> processed.contains("healthy"));
        MatcherAssert.assertThat(
            "The permanently-failing item must be retried exactly 3 times, then dropped",
            failingAttempts.get(),
            Matchers.is(3)
        );
    }

    @Test
    @Timeout(10)
    void anUnexpectedExceptionInOneTickDoesNotStopFutureDrains() {
        // ScheduledExecutorService#scheduleAtFixedRate silently cancels all
        // future executions of a task whose Runnable escapes with an
        // exception. LocalEventDrainScheduler must guard against that: one
        // bad tick (a RuntimeException from a misbehaving consumer, not the
        // expected EventProcessingError) must not permanently stop the
        // drain — proven here by adding a second, healthy item after the
        // first tick has already thrown and observing it still gets
        // processed on a later tick.
        final Queue<String> queue = new ConcurrentLinkedDeque<>();
        queue.add("boom");
        final AtomicInteger calls = new AtomicInteger();
        final List<String> processed = new CopyOnWriteArrayList<>();
        final Consumer<String> action = item -> {
            calls.incrementAndGet();
            if ("boom".equals(item)) {
                throw new IllegalStateException("unexpected");
            }
            processed.add(item);
        };
        this.scheduler = new LocalEventDrainScheduler<>(queue, List.of(action), 1);
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> calls.get() >= 1);
        queue.add("after-the-throw");
        Awaitility.await().atMost(10, TimeUnit.SECONDS)
            .until(() -> processed.contains("after-the-throw"));
    }

    @Test
    @Timeout(10)
    void closeStopsFurtherDraining() {
        final Queue<String> queue = new ConcurrentLinkedDeque<>();
        final CountingConsumer consumer = new CountingConsumer();
        this.scheduler = new LocalEventDrainScheduler<>(queue, List.of(consumer), 1);
        queue.add("one");
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> consumer.count.get() == 1);
        this.scheduler.close();
        final int countAtClose = consumer.count.get();
        queue.add("two");
        // Regression guard, not a latency assertion: the drain interval is
        // 1s, so an improperly-closed scheduler would drain "two" within
        // that window. pollDelay gives that window a chance to fire before
        // asserting the count never moved — close() must be immediately
        // effective, not just eventually consistent.
        Awaitility.await()
            .pollDelay(1, TimeUnit.SECONDS)
            .atMost(3, TimeUnit.SECONDS)
            .until(() -> consumer.count.get() == countAtClose);
        MatcherAssert.assertThat(
            "A closed scheduler must not keep draining items added afterward",
            consumer.count.get(),
            Matchers.is(countAtClose)
        );
        this.scheduler = null;
    }

    /**
     * Simple invocation-counting consumer.
     * @since 2.3.0
     */
    private static final class CountingConsumer implements Consumer<String> {

        /**
         * Number of times {@link #accept(String)} was called.
         */
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public void accept(final String item) {
            this.count.incrementAndGet();
        }
    }
}
