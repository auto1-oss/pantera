/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto.events;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link EventQueue}.
 */
class EventQueueTest {

    @Test
    void acceptsItemsUpToCapacity() {
        final int cap = 5;
        final EventQueue<String> queue = new EventQueue<>(cap);
        for (int idx = 0; idx < cap; idx += 1) {
            assertThat(queue.put("item-" + idx), is(true));
        }
        final List<String> items = new ArrayList<>(cap);
        while (!queue.isEmpty()) {
            items.add(queue.poll());
        }
        assertThat(items.size(), equalTo(cap));
    }

    @Test
    void dropsItemsWhenFull() {
        final int cap = 3;
        final EventQueue<String> queue = new EventQueue<>(cap);
        for (int idx = 0; idx < cap; idx += 1) {
            queue.put("item-" + idx);
        }
        assertThat(
            "item beyond capacity should be dropped",
            queue.put("overflow"),
            is(false)
        );
        int count = 0;
        while (!queue.isEmpty()) {
            queue.poll();
            count += 1;
        }
        assertThat(count, equalTo(cap));
    }

    @Test
    void defaultCapacityIs10000() {
        final EventQueue<Integer> queue = new EventQueue<>();
        for (int idx = 0; idx < EventQueue.DEFAULT_CAPACITY; idx += 1) {
            assertThat(queue.put(idx), is(true));
        }
        assertThat(
            "item beyond default capacity should be dropped",
            queue.put(99999),
            is(false)
        );
    }

    @Test
    void customCapacityWorks() {
        final EventQueue<String> queue = new EventQueue<>(2);
        assertThat(queue.put("a"), is(true));
        assertThat(queue.put("b"), is(true));
        assertThat(queue.put("c"), is(false));
    }

    @Test
    void concurrentPutsRespectCapacity() throws Exception {
        final int cap = 100;
        final EventQueue<Integer> queue = new EventQueue<>(cap);
        final int threads = 10;
        final int perThread = 50;
        final ExecutorService exec = Executors.newFixedThreadPool(threads);
        final CountDownLatch latch = new CountDownLatch(threads);
        for (int thr = 0; thr < threads; thr += 1) {
            final int offset = thr * perThread;
            exec.submit(() -> {
                for (int idx = 0; idx < perThread; idx += 1) {
                    queue.put(offset + idx);
                }
                latch.countDown();
            });
        }
        latch.await(10, TimeUnit.SECONDS);
        exec.shutdown();
        int count = 0;
        while (!queue.isEmpty()) {
            queue.poll();
            count += 1;
        }
        assertThat(
            "queue should not exceed capacity",
            count,
            lessThanOrEqualTo(cap)
        );
    }

    @Test
    void queueAccessorReturnsSameQueue() {
        final EventQueue<String> queue = new EventQueue<>(5);
        queue.put("test");
        assertThat(queue.queue(), notNullValue());
        assertThat(queue.queue().isEmpty(), is(false));
    }

    @Test
    void throwsForInvalidCapacity() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new EventQueue<>(0)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new EventQueue<>(-1)
        );
    }

    @Test
    void pollDecrementsSize() {
        final EventQueue<String> queue = new EventQueue<>(5);
        queue.put("a");
        queue.put("b");
        queue.poll();
        assertThat(
            "after poll, should accept new items",
            queue.put("c"),
            is(true)
        );
    }
}
