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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.log.EcsMdc;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Test for {@link ProxyEventRetry}.
 */
final class ProxyEventRetryTest {

    @Test
    void givesUpAfterTheAttemptLimit() {
        final Queue<ProxyArtifactEvent> queue = new ConcurrentLinkedQueue<>();
        final AtomicLong now = new AtomicLong(1_000_000L);
        final ProxyEventRetry retry = new ProxyEventRetry(queue, now::get);
        queue.add(new ProxyArtifactEvent(new Key.From("a/b"), "repo"));
        int rounds = 0;
        while (!queue.isEmpty() && rounds < 100) {
            now.addAndGet(120_000L);
            for (final ProxyArtifactEvent event : retry.due(100)) {
                retry.again(event, "test");
            }
            rounds += 1;
        }
        MatcherAssert.assertThat(
            "the event is put back MAX_ATTEMPTS times and then dropped",
            rounds, new IsEqual<>(ProxyEventRetry.MAX_ATTEMPTS + 1)
        );
        MatcherAssert.assertThat(
            "nothing is left in the queue", queue.isEmpty(), new IsEqual<>(true)
        );
    }

    @Test
    void leavesAnEventQueuedUntilItsRetryTime() {
        final Queue<ProxyArtifactEvent> queue = new ConcurrentLinkedQueue<>();
        final AtomicLong now = new AtomicLong(1_000_000L);
        final ProxyEventRetry retry = new ProxyEventRetry(queue, now::get);
        retry.again(new ProxyArtifactEvent(new Key.From("a/b"), "repo"), "test");
        MatcherAssert.assertThat(
            "not due at once", retry.due(100).size(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "still queued while it waits", queue.size(), new IsEqual<>(1)
        );
        now.addAndGet(999L);
        MatcherAssert.assertThat(
            "not due before the first wait is over", retry.due(100).size(), new IsEqual<>(0)
        );
        now.addAndGet(1L);
        final List<ProxyArtifactEvent> due = retry.due(100);
        MatcherAssert.assertThat("due after one second", due.size(), new IsEqual<>(1));
        MatcherAssert.assertThat(
            "the attempt is counted on the event", due.get(0).attempts(), new IsEqual<>(1)
        );
    }

    @Test
    void waitsLongerEachTimeUpToACap() {
        final Queue<ProxyArtifactEvent> queue = new ConcurrentLinkedQueue<>();
        final ProxyEventRetry retry = new ProxyEventRetry(queue, () -> 0L);
        ProxyArtifactEvent event = new ProxyArtifactEvent(new Key.From("a/b"), "repo");
        final long[] waits = new long[ProxyEventRetry.MAX_ATTEMPTS];
        for (int idx = 0; idx < waits.length; idx += 1) {
            retry.again(event, "test");
            event = queue.poll();
            waits[idx] = event.notBefore();
        }
        MatcherAssert.assertThat(
            waits,
            new IsEqual<>(
                new long[] {1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L}
            )
        );
    }

    @Test
    void returnsWaitingEventsBehindDueOnes() {
        final Queue<ProxyArtifactEvent> queue = new ConcurrentLinkedQueue<>();
        final ProxyEventRetry retry = new ProxyEventRetry(queue, () -> 5_000L);
        queue.add(new ProxyArtifactEvent(new Key.From("late"), "repo").retried(9_000L));
        queue.add(new ProxyArtifactEvent(new Key.From("fresh"), "repo"));
        final List<ProxyArtifactEvent> due = retry.due(100);
        MatcherAssert.assertThat(
            "only the fresh event is handed out",
            due.get(0).artifactKey().string(), new IsEqual<>("fresh")
        );
        MatcherAssert.assertThat(
            "the waiting one stays queued",
            queue.peek().artifactKey().string(), new IsEqual<>("late")
        );
    }

    @Test
    void keepsTheRequestContextOfARetriedEvent() {
        MDC.put(EcsMdc.TRACE_ID, "trace-1");
        MDC.put(EcsMdc.CLIENT_IP, "10.0.0.1");
        final ProxyArtifactEvent first;
        try {
            first = new ProxyArtifactEvent(new Key.From("a/b"), "repo");
        } finally {
            MDC.clear();
        }
        final ProxyArtifactEvent again = first.retried(1L);
        MatcherAssert.assertThat(
            "trace id survives the retry", again.traceId(), new IsEqual<>("trace-1")
        );
        MatcherAssert.assertThat(
            "client ip survives the retry", again.clientIp(), new IsEqual<>("10.0.0.1")
        );
    }

    @Test
    void dropsTheRetryWhenTheQueueIsFull() {
        final Queue<ProxyArtifactEvent> queue = new LinkedBlockingQueue<>(1);
        final ProxyEventRetry retry = new ProxyEventRetry(queue, () -> 0L);
        queue.add(new ProxyArtifactEvent(new Key.From("other"), "repo"));
        MatcherAssert.assertThat(
            retry.again(new ProxyArtifactEvent(new Key.From("a/b"), "repo"), "test"),
            new IsEqual<>(false)
        );
    }
}
