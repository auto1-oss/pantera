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
package com.auto1.pantera.http.timeout;

import com.auto1.pantera.http.log.EcsLogger;

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
                EcsLogger.info("com.auto1.pantera.http.timeout")
                    .message("Circuit breaker transition BLOCKED → PROBING — block expired")
                    .eventCategory("web")
                    .eventAction("circuit_breaker_probing")
                    .eventOutcome("success")
                    .field("remote.id", remoteId)
                    .log();
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
        // Use a holder so we can detect-and-log the CLOSED→OPEN transition
        // outside the compute() lambda (logging inside would be called
        // under the ConcurrentHashMap bin lock — cheap in practice but
        // we avoid it on principle).
        final BlockState[] previous = new BlockState[1];
        final BlockState[] updated = new BlockState[1];
        this.states.compute(remoteId, (key, current) -> {
            final BlockState state =
                current != null ? current : BlockState.online();
            previous[0] = state;
            // If already actively blocked, don't extend the duration.
            // Under high traffic every in-flight request hits this path and would
            // keep resetting blockedUntil to "now + maxBlockDuration", preventing
            // the circuit from ever transitioning to PROBING and self-healing.
            if (state.status() == BlockState.Status.BLOCKED
                && !Instant.now().isAfter(state.blockedUntil())) {
                updated[0] = state;
                return state;
            }
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
                updated[0] = new BlockState(
                    failures, fibIdx, Instant.now().plusMillis(blockMs),
                    BlockState.Status.BLOCKED
                );
                return updated[0];
            }
            updated[0] = new BlockState(
                failures, state.fibonacciIndex(),
                state.blockedUntil(), state.status()
            );
            return updated[0];
        });
        if (previous[0].status() != BlockState.Status.BLOCKED
            && updated[0].status() == BlockState.Status.BLOCKED) {
            // ONLINE or PROBING → BLOCKED: the circuit just tripped.
            final long blockMillis = updated[0].blockedUntil().toEpochMilli()
                - Instant.now().toEpochMilli();
            EcsLogger.warn("com.auto1.pantera.http.timeout")
                .message("Circuit breaker OPENED — upstream blocked after "
                    + updated[0].failureCount() + " consecutive failures")
                .eventCategory("web")
                .eventAction("circuit_breaker_opened")
                .eventOutcome("failure")
                .field("event.reason", "failure_threshold_reached")
                .field("remote.id", remoteId)
                .field("failure.count", updated[0].failureCount())
                .field("failure.threshold", this.settings.failureThreshold())
                .field("block.duration_ms", blockMillis)
                .field("block.until", updated[0].blockedUntil().toString())
                .log();
        }
    }

    /**
     * Record a success for a remote. Resets to ONLINE state.
     */
    public void recordSuccess(final String remoteId) {
        final BlockState previous = this.states.put(remoteId, BlockState.online());
        // Only log the PROBING/BLOCKED → ONLINE recovery transition, not
        // every single successful request. ONLINE → ONLINE is the normal
        // case and should stay silent to avoid access-log volume.
        if (previous != null
            && previous.status() != BlockState.Status.ONLINE) {
            EcsLogger.info("com.auto1.pantera.http.timeout")
                .message("Circuit breaker CLOSED — upstream recovered")
                .eventCategory("web")
                .eventAction("circuit_breaker_closed")
                .eventOutcome("success")
                .field("remote.id", remoteId)
                .field("previous.status",
                    previous.status().name().toLowerCase(Locale.ROOT))
                .log();
        }
    }
}
