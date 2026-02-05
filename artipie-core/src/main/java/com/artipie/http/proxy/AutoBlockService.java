/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.proxy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker service that auto-blocks failing upstreams.
 * Prevents cascading failures by temporarily blocking requests to
 * upstreams that are failing, allowing them time to recover.
 *
 * <p>States:</p>
 * <ul>
 *   <li>CLOSED - Normal operation, requests flow through</li>
 *   <li>OPEN - Blocked, requests fail fast without hitting upstream</li>
 *   <li>HALF_OPEN - Testing recovery, limited requests allowed through</li>
 * </ul>
 *
 * <p>Transition rules:</p>
 * <ul>
 *   <li>CLOSED -> OPEN: When failure threshold exceeded in window</li>
 *   <li>OPEN -> HALF_OPEN: After block duration expires</li>
 *   <li>HALF_OPEN -> CLOSED: On successful probe request</li>
 *   <li>HALF_OPEN -> OPEN: On failed probe request</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * AutoBlockService autoBlock = new AutoBlockService(
 *     5,  // failure threshold
 *     Duration.ofMinutes(1),  // evaluation window
 *     Duration.ofMinutes(5)   // block duration
 * );
 *
 * // Check before making request
 * if (autoBlock.isBlocked("https://registry.npmjs.org")) {
 *     return ResponseBuilder.serviceUnavailable()
 *         .header("Retry-After", "300")
 *         .build();
 * }
 *
 * // Record result after request
 * try {
 *     Response response = fetchFromUpstream(...);
 *     autoBlock.recordSuccess("https://registry.npmjs.org");
 *     return response;
 * } catch (Exception e) {
 *     autoBlock.recordFailure("https://registry.npmjs.org", e);
 *     throw e;
 * }
 * }</pre>
 *
 * @since 1.0
 */
public final class AutoBlockService {

    /**
     * Default failure threshold (failures within window to trigger block).
     */
    public static final int DEFAULT_FAILURE_THRESHOLD = 5;

    /**
     * Default evaluation window.
     */
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);

    /**
     * Default block duration.
     */
    public static final Duration DEFAULT_BLOCK_DURATION = Duration.ofMinutes(5);

    /**
     * Circuit state per upstream.
     */
    private final Map<String, CircuitState> circuits;

    /**
     * Failure threshold to trigger block.
     */
    private final int failureThreshold;

    /**
     * Time window for counting failures.
     */
    private final Duration window;

    /**
     * Duration to block upstream after threshold exceeded.
     */
    private final Duration blockDuration;

    /**
     * Create with default settings.
     */
    public AutoBlockService() {
        this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_WINDOW, DEFAULT_BLOCK_DURATION);
    }

    /**
     * Create with custom settings.
     *
     * @param failureThreshold Failures to trigger block
     * @param window Time window for failure counting
     * @param blockDuration Duration to block upstream
     */
    public AutoBlockService(
        final int failureThreshold,
        final Duration window,
        final Duration blockDuration
    ) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be >= 1");
        }
        this.failureThreshold = failureThreshold;
        this.window = window;
        this.blockDuration = blockDuration;
        this.circuits = new ConcurrentHashMap<>();
    }

    /**
     * Check if upstream is currently blocked.
     *
     * @param upstream Upstream identifier (URL or name)
     * @return True if blocked and should not attempt request
     */
    public boolean isBlocked(final String upstream) {
        final CircuitState state = this.circuits.get(upstream);
        if (state == null) {
            return false;
        }
        return state.isBlocked();
    }

    /**
     * Get current state for upstream.
     *
     * @param upstream Upstream identifier
     * @return Circuit state (CLOSED if no state exists)
     */
    public State getState(final String upstream) {
        final CircuitState state = this.circuits.get(upstream);
        if (state == null) {
            return State.CLOSED;
        }
        return state.currentState();
    }

    /**
     * Record successful request to upstream.
     * Resets failure count and may close circuit.
     *
     * @param upstream Upstream identifier
     */
    public void recordSuccess(final String upstream) {
        final CircuitState state = this.circuits.get(upstream);
        if (state != null) {
            state.recordSuccess();
        }
    }

    /**
     * Maximum number of tracked upstreams to prevent memory leaks.
     */
    private static final int MAX_TRACKED_UPSTREAMS = 1000;

    /**
     * Record failed request to upstream.
     * May trigger circuit open if threshold exceeded.
     *
     * @param upstream Upstream identifier
     * @param error Error that occurred
     */
    public void recordFailure(final String upstream, final Throwable error) {
        // Cleanup stale entries if map is getting large
        if (this.circuits.size() > MAX_TRACKED_UPSTREAMS) {
            this.cleanupStaleEntries();
        }
        this.circuits.computeIfAbsent(
            upstream,
            k -> new CircuitState(this.failureThreshold, this.window, this.blockDuration)
        ).recordFailure(error);
    }

    /**
     * Remove stale entries (CLOSED circuits with no recent failures).
     */
    private void cleanupStaleEntries() {
        final Instant threshold = Instant.now().minus(this.blockDuration.multipliedBy(2));
        this.circuits.entrySet().removeIf(entry -> {
            final CircuitState state = entry.getValue();
            // Remove CLOSED circuits with no recent activity
            return state.currentState() == State.CLOSED
                && state.failureCount() == 0
                && state.getWindowStart().isBefore(threshold);
        });
    }

    /**
     * Manually reset circuit for upstream.
     * Forces circuit back to CLOSED state.
     *
     * @param upstream Upstream identifier
     */
    public void reset(final String upstream) {
        this.circuits.remove(upstream);
    }

    /**
     * Get remaining block time for upstream.
     *
     * @param upstream Upstream identifier
     * @return Remaining block duration, or Duration.ZERO if not blocked
     */
    public Duration remainingBlockTime(final String upstream) {
        final CircuitState state = this.circuits.get(upstream);
        if (state == null) {
            return Duration.ZERO;
        }
        return state.remainingBlockTime();
    }

    /**
     * Get failure count for upstream in current window.
     *
     * @param upstream Upstream identifier
     * @return Current failure count
     */
    public int failureCount(final String upstream) {
        final CircuitState state = this.circuits.get(upstream);
        if (state == null) {
            return 0;
        }
        return state.failureCount();
    }

    /**
     * Get all tracked upstreams.
     *
     * @return Map of upstream to state
     */
    public Map<String, State> allStates() {
        final Map<String, State> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, CircuitState> entry : this.circuits.entrySet()) {
            result.put(entry.getKey(), entry.getValue().currentState());
        }
        return result;
    }

    /**
     * Circuit breaker state.
     */
    public enum State {
        /**
         * Normal operation - requests flow through.
         */
        CLOSED,
        /**
         * Blocked - requests fail fast.
         */
        OPEN,
        /**
         * Testing recovery - limited requests allowed.
         */
        HALF_OPEN
    }

    /**
     * Internal circuit state tracker per upstream.
     */
    private static final class CircuitState {

        /**
         * Current state.
         */
        private final AtomicReference<State> state;

        /**
         * Failure count in current window.
         */
        private final AtomicInteger failures;

        /**
         * Window start time.
         */
        private final AtomicReference<Instant> windowStart;

        /**
         * Time when circuit opened.
         */
        private final AtomicReference<Instant> openedAt;

        /**
         * Failure threshold.
         */
        private final int threshold;

        /**
         * Window duration.
         */
        private final Duration window;

        /**
         * Block duration.
         */
        private final Duration blockDuration;

        CircuitState(
            final int threshold,
            final Duration window,
            final Duration blockDuration
        ) {
            this.threshold = threshold;
            this.window = window;
            this.blockDuration = blockDuration;
            this.state = new AtomicReference<>(State.CLOSED);
            this.failures = new AtomicInteger(0);
            this.windowStart = new AtomicReference<>(Instant.now());
            this.openedAt = new AtomicReference<>(null);
        }

        boolean isBlocked() {
            final State current = this.currentState();
            return current == State.OPEN;
        }

        State currentState() {
            final State current = this.state.get();
            if (current == State.OPEN) {
                // Check if block duration expired
                final Instant opened = this.openedAt.get();
                if (opened != null
                    && Instant.now().isAfter(opened.plus(this.blockDuration))) {
                    // Transition to half-open
                    this.state.compareAndSet(State.OPEN, State.HALF_OPEN);
                    return State.HALF_OPEN;
                }
            }
            return this.state.get();
        }

        void recordSuccess() {
            final State current = this.state.get();
            if (current == State.HALF_OPEN) {
                // Successful probe - close circuit
                this.state.set(State.CLOSED);
                this.failures.set(0);
                this.windowStart.set(Instant.now());
                this.openedAt.set(null);
            } else if (current == State.CLOSED) {
                // Reset failure count on success in closed state
                this.failures.set(0);
            }
        }

        void recordFailure(final Throwable error) {
            final State current = this.currentState();

            if (current == State.HALF_OPEN) {
                // Failed probe - reopen circuit
                this.state.set(State.OPEN);
                this.openedAt.set(Instant.now());
                return;
            }

            if (current == State.OPEN) {
                // Already blocked - ignore
                return;
            }

            // CLOSED state - count failure
            final Instant now = Instant.now();
            final Instant wstart = this.windowStart.get();

            if (now.isAfter(wstart.plus(this.window))) {
                // Window expired - reset
                this.windowStart.set(now);
                this.failures.set(1);
            } else {
                // Count failure in current window
                final int count = this.failures.incrementAndGet();
                if (count >= this.threshold) {
                    // Threshold exceeded - open circuit
                    this.state.set(State.OPEN);
                    this.openedAt.set(now);
                }
            }
        }

        Duration remainingBlockTime() {
            if (this.state.get() != State.OPEN) {
                return Duration.ZERO;
            }
            final Instant opened = this.openedAt.get();
            if (opened == null) {
                return Duration.ZERO;
            }
            final Instant unblockTime = opened.plus(this.blockDuration);
            final Duration remaining = Duration.between(Instant.now(), unblockTime);
            return remaining.isNegative() ? Duration.ZERO : remaining;
        }

        int failureCount() {
            // Check if window expired
            final Instant now = Instant.now();
            final Instant wstart = this.windowStart.get();
            if (now.isAfter(wstart.plus(this.window))) {
                return 0;
            }
            return this.failures.get();
        }

        Instant getWindowStart() {
            return this.windowStart.get();
        }
    }
}
