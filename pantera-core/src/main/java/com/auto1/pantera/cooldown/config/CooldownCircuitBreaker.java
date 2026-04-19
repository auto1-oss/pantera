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
package com.auto1.pantera.cooldown.config;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker for cooldown service to prevent cascading failures.
 * Automatically degrades to ALLOW mode if cooldown service is slow or failing.
 *
 * States:
 * - CLOSED: Normal operation, all requests evaluated
 * - OPEN: Too many failures, automatically allow all requests
 * - HALF_OPEN: Testing if service recovered
 *
 * @since 1.0
 */
public final class CooldownCircuitBreaker {

    /**
     * Circuit breaker state.
     */
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /**
     * Current state.
     */
    private final AtomicReference<State> state;

    /**
     * Consecutive failure count.
     */
    private final AtomicInteger failures;

    /**
     * Time when circuit was opened.
     */
    private final AtomicLong openedAt;

    /**
     * Total requests processed.
     */
    private final AtomicLong totalRequests;

    /**
     * Total requests allowed due to open circuit.
     */
    private final AtomicLong autoAllowed;

    /**
     * Failure threshold before opening circuit.
     */
    private final int failureThreshold;

    /**
     * Duration to wait before attempting recovery.
     */
    private final Duration recoveryTimeout;

    /**
     * Success threshold in HALF_OPEN state before closing circuit.
     */
    private final int successThreshold;

    /**
     * Consecutive successes in HALF_OPEN state.
     */
    private final AtomicInteger halfOpenSuccesses;

    /**
     * Constructor with default settings.
     * - Failure threshold: 5
     * - Recovery timeout: 30 seconds
     * - Success threshold: 2
     */
    public CooldownCircuitBreaker() {
        this(5, Duration.ofSeconds(30), 2);
    }

    /**
     * Constructor with custom settings.
     *
     * @param failureThreshold Failures before opening circuit
     * @param recoveryTimeout Time to wait before recovery attempt
     * @param successThreshold Successes in HALF_OPEN before closing
     */
    public CooldownCircuitBreaker(
        final int failureThreshold,
        final Duration recoveryTimeout,
        final int successThreshold
    ) {
        this.state = new AtomicReference<>(State.CLOSED);
        this.failures = new AtomicInteger(0);
        this.openedAt = new AtomicLong(0);
        this.totalRequests = new AtomicLong(0);
        this.autoAllowed = new AtomicLong(0);
        this.failureThreshold = failureThreshold;
        this.recoveryTimeout = recoveryTimeout;
        this.successThreshold = successThreshold;
        this.halfOpenSuccesses = new AtomicInteger(0);
    }

    /**
     * Check if request should be evaluated or auto-allowed.
     *
     * @return True if should evaluate, false if should auto-allow
     */
    public boolean shouldEvaluate() {
        this.totalRequests.incrementAndGet();
        
        final State current = this.state.get();
        
        if (current == State.CLOSED) {
            return true;
        }
        
        if (current == State.OPEN) {
            // Check if recovery timeout elapsed
            final long openTime = this.openedAt.get();
            if (openTime > 0) {
                final long elapsed = System.currentTimeMillis() - openTime;
                if (elapsed >= this.recoveryTimeout.toMillis()) {
                    // Transition to HALF_OPEN
                    if (this.state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        this.halfOpenSuccesses.set(0);
                        return true;
                    }
                }
            }
            // Still open, auto-allow
            this.autoAllowed.incrementAndGet();
            return false;
        }
        
        // HALF_OPEN: Allow some requests through to test
        return true;
    }

    /**
     * Record successful evaluation.
     */
    public void recordSuccess() {
        final State current = this.state.get();
        
        if (current == State.CLOSED) {
            // Reset failure counter on success
            this.failures.set(0);
            return;
        }
        
        if (current == State.HALF_OPEN) {
            final int successes = this.halfOpenSuccesses.incrementAndGet();
            if (successes >= this.successThreshold) {
                // Recovered! Close circuit
                if (this.state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    this.failures.set(0);
                    this.openedAt.set(0);
                }
            }
        }
    }

    /**
     * Record failed evaluation.
     */
    public void recordFailure() {
        final State current = this.state.get();
        
        if (current == State.CLOSED) {
            final int count = this.failures.incrementAndGet();
            if (count >= this.failureThreshold) {
                // Open circuit
                if (this.state.compareAndSet(State.CLOSED, State.OPEN)) {
                    this.openedAt.set(System.currentTimeMillis());
                }
            }
            return;
        }
        
        if (current == State.HALF_OPEN) {
            // Failed during recovery test, reopen circuit
            if (this.state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                this.openedAt.set(System.currentTimeMillis());
                this.failures.set(this.failureThreshold);
            }
        }
    }

    /**
     * Get current state.
     *
     * @return Current state
     */
    public State getState() {
        return this.state.get();
    }

    /**
     * Get statistics.
     *
     * @return Statistics string
     */
    public String stats() {
        final long total = this.totalRequests.get();
        final long allowed = this.autoAllowed.get();
        final double allowRate = total == 0 ? 0.0 : (double) allowed / total * 100;
        
        return String.format(
            "CircuitBreaker[state=%s, failures=%d, total=%d, autoAllowed=%d (%.1f%%)]",
            this.state.get(),
            this.failures.get(),
            total,
            allowed,
            allowRate
        );
    }

    /**
     * Reset circuit breaker.
     */
    public void reset() {
        this.state.set(State.CLOSED);
        this.failures.set(0);
        this.openedAt.set(0);
        this.halfOpenSuccesses.set(0);
        this.totalRequests.set(0);
        this.autoAllowed.set(0);
    }
}
