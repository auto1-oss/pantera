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
package com.auto1.pantera.http.log;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Decides which occurrences of a repeating condition are worth a log
 * record: the first one per key, then at most one per interval, which
 * reports how many occurrences were not logged in between.
 *
 * <p>For a condition that is true on every request until an operator
 * changes a setting (credentials withheld from an untrusted host, ...):
 * logged per request it buries the log stream without adding information,
 * not logged at all it is invisible. The record of the first occurrence
 * says what to do; the later ones say that it is still happening and how
 * often.</p>
 *
 * <p>Memory is bounded: at most {@code keys} keys are tracked, and a key
 * not seen for two intervals is forgotten. A key that fell out is treated
 * as new, so it is logged again, never lost.</p>
 *
 * <pre>{@code
 * THROTTLE.admit(upstream + "|" + host).ifPresent(
 *     skipped -> EcsLogger.warn(...).message("... (" + skipped + " more not logged)").log()
 * );
 * }</pre>
 *
 * @since 2.2.14
 */
public final class LogThrottle {

    /**
     * Minimal time between two records of one key, milliseconds.
     */
    private final long interval;

    /**
     * Clock, milliseconds.
     */
    private final LongSupplier clock;

    /**
     * State per key.
     */
    private final Cache<String, State> states;

    /**
     * One record per key and hour, 1024 keys.
     */
    public LogThrottle() {
        this(Duration.ofHours(1), 1024);
    }

    /**
     * Ctor with the system clock.
     * @param interval Minimal time between two records of one key
     * @param keys Maximum number of keys tracked
     */
    public LogThrottle(final Duration interval, final int keys) {
        this(interval, keys, System::currentTimeMillis);
    }

    /**
     * Primary ctor.
     * @param interval Minimal time between two records of one key
     * @param keys Maximum number of keys tracked
     * @param clock Clock in milliseconds
     */
    public LogThrottle(final Duration interval, final int keys, final LongSupplier clock) {
        this.interval = interval.toMillis();
        this.clock = clock;
        this.states = Caffeine.newBuilder()
            .maximumSize(keys)
            .expireAfterAccess(interval.multipliedBy(2))
            .build();
    }

    /**
     * Register one occurrence.
     *
     * @param key What the condition is about (bounded text, e.g. two hosts)
     * @return The number of occurrences not logged since the last record of
     *  this key when this one is to be logged (0 for the first), empty when
     *  it is to be skipped
     */
    public OptionalLong admit(final String key) {
        final long now = this.clock.getAsLong();
        final State state = this.states.get(key, ignored -> new State());
        return state.admit(now, this.interval);
    }

    /**
     * Last record and skipped occurrences of one key.
     */
    private static final class State {

        /**
         * Time of the last record, or {@link Long#MIN_VALUE} before the first.
         */
        private long last = Long.MIN_VALUE;

        /**
         * Occurrences skipped since the last record.
         */
        private final AtomicLong skipped = new AtomicLong();

        synchronized OptionalLong admit(final long now, final long interval) {
            final OptionalLong result;
            if (this.last == Long.MIN_VALUE || now - this.last >= interval) {
                this.last = now;
                result = OptionalLong.of(this.skipped.getAndSet(0L));
            } else {
                this.skipped.incrementAndGet();
                result = OptionalLong.empty();
            }
            return result;
        }
    }
}
