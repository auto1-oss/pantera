/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.group;

import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.jcabi.log.Logger;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Member repository slice with circuit breaker for failure isolation.
 * 
 * <p>Circuit breaker states:
 * <ul>
 *   <li>CLOSED: Normal operation, requests pass through</li>
 *   <li>OPEN: Fast-fail mode, requests rejected immediately (after N failures)</li>
 *   <li>HALF_OPEN: Testing recovery, allow one request through</li>
 * </ul>
 * 
 * <p>Circuit breaker thresholds:
 * <ul>
 *   <li>Open after 5 consecutive failures</li>
 *   <li>Stay open for 30 seconds</li>
 *   <li>Reset counter on first success</li>
 * </ul>
 * 
 * @since 1.18.23
 */
public final class MemberSlice {

    /**
     * Number of consecutive failures before opening circuit.
     */
    private static final int FAILURE_THRESHOLD = 5;

    /**
     * How long to keep circuit open before attempting recovery.
     */
    private static final Duration RESET_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Member repository name.
     */
    private final String name;

    /**
     * Underlying slice for this member.
     */
    private final Slice delegate;

    /**
     * Consecutive failure count.
     */
    private final AtomicInteger failureCount = new AtomicInteger(0);

    /**
     * When circuit was opened (null if closed).
     */
    private volatile Instant openedAt = null;

    /**
     * Constructor.
     * 
     * @param name Member repository name
     * @param delegate Underlying slice
     */
    public MemberSlice(final String name, final Slice delegate) {
        this.name = Objects.requireNonNull(name, "name");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Get member repository name.
     * 
     * @return Member name
     */
    public String name() {
        return this.name;
    }

    /**
     * Get underlying slice.
     * 
     * @return Delegate slice
     */
    public Slice slice() {
        return this.delegate;
    }

    /**
     * Check if circuit breaker is in OPEN state.
     * 
     * @return True if circuit is open (fast-failing)
     */
    public boolean isCircuitOpen() {
        if (this.openedAt == null) {
            return false;
        }
        
        // Check if timeout has expired (transition to HALF_OPEN)
        final Duration elapsed = Duration.between(this.openedAt, Instant.now());
        if (elapsed.compareTo(RESET_TIMEOUT) >= 0) {
            Logger.info(
                this,
                "Circuit breaker for %s entering HALF_OPEN state (elapsed=%dms)",
                this.name,
                elapsed.toMillis()
            );
            this.openedAt = null;
            return false;
        }
        
        return true;
    }

    /**
     * Record successful response from this member.
     * Resets circuit breaker state.
     */
    public void recordSuccess() {
        final int previousFailures = this.failureCount.getAndSet(0);
        if (previousFailures > 0) {
            Logger.info(
                this,
                "Member %s recovered after %d failures, circuit breaker CLOSED",
                this.name,
                previousFailures
            );
        }
        this.openedAt = null;
    }

    /**
     * Record failed response from this member.
     * May open circuit breaker if threshold exceeded.
     */
    public void recordFailure() {
        final int failures = this.failureCount.incrementAndGet();
        
        if (failures >= FAILURE_THRESHOLD && this.openedAt == null) {
            this.openedAt = Instant.now();
            Logger.warn(
                this,
                "Circuit breaker OPENED for member %s after %d consecutive failures",
                this.name,
                failures
            );
        } else if (failures < FAILURE_THRESHOLD) {
            Logger.debug(
                this,
                "Member %s failure count: %d/%d",
                this.name,
                failures,
                FAILURE_THRESHOLD
            );
        }
    }

    /**
     * Rewrite request path to include member repository name.
     * 
     * <p>Transforms: /path → /member/path
     * 
     * @param original Original request line
     * @return Rewritten request line with member prefix
     */
    public RequestLine rewritePath(final RequestLine original) {
        final URI uri = original.uri();
        final String raw = uri.getRawPath();
        final String base = raw.startsWith("/") ? raw : "/" + raw;
        final String prefix = "/" + this.name + "/";
        
        // Avoid double-prefixing
        final String path = base.startsWith(prefix) ? base : ("/" + this.name + base);
        
        final StringBuilder full = new StringBuilder(path);
        if (uri.getRawQuery() != null) {
            full.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            full.append('#').append(uri.getRawFragment());
        }
        
        return new RequestLine(
            original.method().value(),
            full.toString(),
            original.version()
        );
    }

    /**
     * Get current failure count for monitoring.
     * 
     * @return Number of consecutive failures
     */
    public int failureCount() {
        return this.failureCount.get();
    }

    /**
     * Get circuit breaker state for monitoring.
     * 
     * @return "OPEN", "HALF_OPEN", or "CLOSED"
     */
    public String circuitState() {
        if (this.openedAt == null) {
            return "CLOSED";
        }
        final Duration elapsed = Duration.between(this.openedAt, Instant.now());
        if (elapsed.compareTo(RESET_TIMEOUT) >= 0) {
            return "HALF_OPEN";
        }
        return "OPEN";
    }

    @Override
    public String toString() {
        return String.format(
            "MemberSlice{name=%s, failures=%d, circuit=%s}",
            this.name,
            this.failureCount.get(),
            circuitState()
        );
    }
}
