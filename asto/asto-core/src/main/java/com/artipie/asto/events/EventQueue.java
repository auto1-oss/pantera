/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.asto.events;

import com.artipie.asto.log.EcsLogger;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded events queue with {@link ConcurrentLinkedQueue} under the hood.
 * Instance of this class can be passed where necessary (to the adapters for example)
 * to add data for processing into the queue.
 *
 * <p>The queue enforces a maximum capacity. When the queue is full,
 * new items are silently dropped and a warning is logged. This prevents
 * unbounded memory growth under sustained load.</p>
 *
 * @param <T> Queue item parameter type.
 * @since 1.17
 */
public final class EventQueue<T> {

    /**
     * Default maximum queue capacity.
     */
    public static final int DEFAULT_CAPACITY = 10_000;

    /**
     * Queue.
     */
    @SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
    private final Queue<T> queue;

    /**
     * Maximum capacity.
     */
    private final int capacity;

    /**
     * Current size tracker.
     */
    private final AtomicInteger size;

    /**
     * Ctor with default capacity.
     */
    public EventQueue() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Ctor with custom capacity.
     * @param capacity Maximum number of items the queue can hold
     */
    public EventQueue(final int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                String.format("Capacity must be positive: %d", capacity)
            );
        }
        this.queue = new ConcurrentLinkedQueue<>();
        this.capacity = capacity;
        this.size = new AtomicInteger(0);
    }

    /**
     * Add item to queue. If queue is at capacity, the item is dropped.
     * @param item Element to add
     * @return True if item was added, false if dropped due to capacity
     */
    public boolean put(final T item) {
        final int current = this.size.getAndIncrement();
        if (current >= this.capacity) {
            this.size.decrementAndGet();
            EcsLogger.warn("com.artipie.asto.events")
                .message(String.format("Event queue full, dropping event: capacity=%d, size=%d", this.capacity, current))
                .eventCategory("events")
                .eventAction("queue_drop")
                .log();
            return false;
        }
        this.queue.add(item);
        return true;
    }

    /**
     * Poll an item from the queue.
     * @return Next item, or null if queue is empty
     */
    T poll() {
        final T item = this.queue.poll();
        if (item != null) {
            this.size.decrementAndGet();
        }
        return item;
    }

    /**
     * Check if queue is empty.
     * @return True if no items in queue
     */
    boolean isEmpty() {
        return this.queue.isEmpty();
    }

    /**
     * Queue, not public intentionally, the queue should be accessible only from this package.
     * @return The queue.
     */
    @SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
    Queue<T> queue() {
        return this.queue;
    }
}
