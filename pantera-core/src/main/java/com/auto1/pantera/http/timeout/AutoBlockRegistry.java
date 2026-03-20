/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.timeout;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe registry tracking auto-block state for remote endpoints.
 * Uses Fibonacci backoff for increasing block durations.
 * Industry-standard approach used by Nexus and Artifactory.
 *
 * @since 1.20.13
 */
public final class AutoBlockRegistry {

    /**
     * Fibonacci multiplier sequence.
     */
    private static final long[] FIBONACCI = {1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89};

    private final AutoBlockSettings settings;
    private final ConcurrentMap<String, BlockState> states;

    public AutoBlockRegistry(final AutoBlockSettings settings) {
        this.settings = settings;
        this.states = new ConcurrentHashMap<>();
    }

    /**
     * Check if a remote is currently blocked.
     * If the block has expired, transitions to PROBING state and returns false.
     */
    public boolean isBlocked(final String remoteId) {
        final BlockState state = this.states.getOrDefault(
            remoteId, BlockState.online()
        );
        if (state.status() == BlockState.Status.BLOCKED) {
            if (Instant.now().isAfter(state.blockedUntil())) {
                this.states.put(
                    remoteId,
                    new BlockState(
                        state.failureCount(), state.fibonacciIndex(),
                        state.blockedUntil(), BlockState.Status.PROBING
                    )
                );
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * Get the current status of a remote: "online", "blocked", or "probing".
     */
    public String status(final String remoteId) {
        final BlockState state = this.states.getOrDefault(
            remoteId, BlockState.online()
        );
        if (state.status() == BlockState.Status.BLOCKED
            && Instant.now().isAfter(state.blockedUntil())) {
            return "probing";
        }
        return state.status().name().toLowerCase(Locale.ROOT);
    }

    /**
     * Record a failure for a remote. If the failure threshold is reached,
     * blocks the remote with Fibonacci-increasing duration.
     */
    public void recordFailure(final String remoteId) {
        this.states.compute(remoteId, (key, current) -> {
            final BlockState state =
                current != null ? current : BlockState.online();
            final int failures = state.failureCount() + 1;
            if (failures >= this.settings.failureThreshold()) {
                final int fibIdx = state.status() == BlockState.Status.ONLINE
                    ? 0
                    : Math.min(
                        state.fibonacciIndex() + 1, FIBONACCI.length - 1
                    );
                final long blockMs = Math.min(
                    this.settings.initialBlockDuration().toMillis()
                        * FIBONACCI[fibIdx],
                    this.settings.maxBlockDuration().toMillis()
                );
                return new BlockState(
                    failures, fibIdx, Instant.now().plusMillis(blockMs),
                    BlockState.Status.BLOCKED
                );
            }
            return new BlockState(
                failures, state.fibonacciIndex(),
                state.blockedUntil(), state.status()
            );
        });
    }

    /**
     * Record a success for a remote. Resets to ONLINE state.
     */
    public void recordSuccess(final String remoteId) {
        this.states.put(remoteId, BlockState.online());
    }
}
