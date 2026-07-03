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
package com.auto1.pantera.http.client.circuitbreaker;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Slice decorator that consults a per-host
 * {@link UpstreamCircuitBreaker} before delegating, fast-fails when
 * open, trips on qualifying failures, and schedules a daemon HEAD
 * probe at each block-expiry instant to test recovery.
 *
 * <p>Wired by
 * {@link com.auto1.pantera.http.client.jetty.JettyClientSlices} as the
 * decorator immediately around the raw Jetty slice — inside any
 * rate-limit decorator. Decision flow per outbound call:
 *
 * <ol>
 *   <li>If the breaker is open: synthesise a 502 ({@code Bad Gateway})
 *       carrying {@code X-Pantera-Circuit-Open: true} and
 *       {@code Retry-After} pointing at the block-expiry instant. The
 *       wrapped slice is never invoked.</li>
 *   <li>Otherwise: delegate. On response, inspect the status; on
 *       exception, inspect the throwable. Either may trip the breaker
 *       via {@link UpstreamCircuitBreaker#recordFailure(int)} or
 *       {@link UpstreamCircuitBreaker#recordFailure(Throwable)}.
 *       A non-trip response calls
 *       {@link UpstreamCircuitBreaker#recordSuccess()}.</li>
 *   <li>When a trip happens, schedule a single HEAD probe at the
 *       block-expiry instant. The probe goes through the wrapped
 *       slice directly, bypassing this decorator's open-check.
 *       On success the breaker is reset; on failure the next backoff
 *       fires and a fresh probe is scheduled.</li>
 * </ol>
 *
 * <p>The synthesised 502 is intentionally categorised the same way as
 * a real upstream 5xx — the M5 status-fidelity path
 * ({@code maven-adapter/CachedProxySlice.mapUpstreamStatus}) maps both
 * to {@code badGateway} so the group resolver continues to the next
 * member without poisoning the shared negative cache. Downstream
 * disambiguation, if needed, uses the {@code X-Pantera-Circuit-Open}
 * marker header.
 *
 * @since 2.2.0
 */
public final class CircuitBreakingClientSlice implements Slice {

    /**
     * Marker header on synthesised 502 responses — distinguishes a
     * locally-generated fast-fail from a real upstream 5xx. The
     * canonical constant lives in pantera-core
     * ({@link UpstreamCircuitOpenException#HEADER}) so adapters and the
     * group resolver share it without depending on this module.
     */
    public static final String CIRCUIT_OPEN_HEADER = UpstreamCircuitOpenException.HEADER;

    /**
     * Request line used for HEAD recovery probes against the
     * upstream's root path. Hosts that 404 on {@code /} are still
     * proving "the upstream is alive" — which is what we care about.
     */
    private static final RequestLine PROBE_LINE = new RequestLine(RqMethod.HEAD, "/");

    /**
     * Wrapped slice — the raw Jetty slice. The probe path calls this
     * directly to bypass our own open-check during recovery testing.
     */
    private final Slice delegate;

    /**
     * Host label used for diagnostics + probe scheduling logic.
     */
    private final String host;

    /**
     * Per-host breaker state.
     */
    private final UpstreamCircuitBreaker breaker;

    /**
     * Live configuration source (trip predicates read per decision so
     * DB-backed admin settings apply without rebuild).
     */
    private final Supplier<CircuitBreakerConfig> config;

    /**
     * Clock for deriving probe delays.
     */
    private final Clock clock;

    /**
     * Executor that runs the daemon HEAD probe at the scheduled time.
     * Single-threaded is fine — probes are rare (once per trip).
     */
    private final ScheduledExecutorService probeExecutor;

    /**
     * Guard against scheduling overlapping probes. CAS-based so a
     * burst of concurrent trips on the same breaker only schedules
     * one probe per block window.
     */
    private final AtomicBoolean probeScheduled = new AtomicBoolean(false);

    /**
     * Requests fast-failed since the breaker last opened. Snapshotted
     * and reset by the recovery log line so operators can size the
     * client-visible impact of each outage window.
     */
    private final AtomicLong fastfails = new AtomicLong();

    /**
     * Wall-clock instant of the trip that opened the current outage
     * window; 0 when closed. Only used to report open-duration on
     * recovery — best-effort, not part of breaker state.
     */
    private final AtomicLong openedAtMs = new AtomicLong();

    /**
     * @param delegate      Raw slice this decorator protects.
     * @param host          Upstream host (used for diagnostics).
     * @param breaker       Per-host breaker state.
     * @param config        Trip predicates and backoff knobs.
     * @param clock         Clock for time-of-day reads.
     * @param probeExecutor Daemon executor used for HEAD probes; must
     *                      have at least one thread.
     */
    public CircuitBreakingClientSlice(
        final Slice delegate,
        final String host,
        final UpstreamCircuitBreaker breaker,
        final Supplier<CircuitBreakerConfig> config,
        final Clock clock,
        final ScheduledExecutorService probeExecutor
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.host = Objects.requireNonNull(host, "host");
        this.breaker = Objects.requireNonNull(breaker, "breaker");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.probeExecutor = Objects.requireNonNull(probeExecutor, "probeExecutor");
    }

    /**
     * Fixed-config convenience constructor (tests, DB-less boots).
     * @param delegate      Raw slice this decorator protects.
     * @param host          Upstream key (used for diagnostics).
     * @param breaker       Per-upstream breaker state.
     * @param config        Immutable configuration.
     * @param clock         Clock for time-of-day reads.
     * @param probeExecutor Daemon executor for HEAD probes.
     */
    public CircuitBreakingClientSlice(
        final Slice delegate,
        final String host,
        final UpstreamCircuitBreaker breaker,
        final CircuitBreakerConfig config,
        final Clock clock,
        final ScheduledExecutorService probeExecutor
    ) {
        this(delegate, host, breaker, () -> config, clock, probeExecutor);
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        if (this.breaker.isOpen()) {
            this.recordFastfailMetric();
            this.fastfails.incrementAndGet();
            return CompletableFuture.completedFuture(synthesise502(this.breaker.timeRemaining()));
        }
        return this.delegate.response(line, headers, body).whenComplete(
            (response, error) -> this.onResponse(response, error)
        );
    }

    /**
     * Increment {@code pantera_circuit_breaker_fastfail_total{upstream_host}}
     * when MicrometerMetrics is wired. No-op in tests + early
     * bootstrap. T-P02b.
     */
    private void recordFastfailMetric() {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordCircuitBreakerFastfail(this.host);
        }
    }

    /**
     * Increment {@code pantera_circuit_breaker_trips_total{upstream_host}}
     * when MicrometerMetrics is wired. Called from {@link #onResponse}
     * each time a qualifying failure trips the breaker. T-P02b.
     */
    private void recordTripMetric() {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordCircuitBreakerTrip(this.host);
        }
    }

    /**
     * Inspect the delegate's terminal state and decide what to do
     * about it: window-record a qualifying failure (which may trip the
     * volume/rate gate), success-record otherwise. On trip, log the
     * transition and schedule the recovery HEAD probe.
     */
    private void onResponse(final Response response, final Throwable error) {
        if (error != null) {
            if (this.breaker.recordFailure(error)) {
                this.onTripped("exception: " + error.getClass().getSimpleName(), 0);
            }
            return;
        }
        if (response == null) {
            return;
        }
        final int status = response.status().code();
        if (this.config.get().shouldTripOnStatus().test(status)) {
            if (this.breaker.recordFailure(status)) {
                this.onTripped("upstream status " + status, status);
            }
        } else {
            this.onSuccess();
        }
    }

    /**
     * Shared trip bookkeeping: metric, ECS transition log (WARN — an
     * upstream just got convicted by the failure-rate window), probe
     * scheduling.
     *
     * @param reason Human-readable trip cause for the log line.
     * @param status Trip status code, or 0 for exception trips.
     */
    private void onTripped(final String reason, final int status) {
        this.recordTripMetric();
        this.openedAtMs.set(this.clock.millis());
        final Duration remaining = this.breaker.timeRemaining();
        final var log = EcsLogger.warn("com.auto1.pantera.http.client.circuitbreaker")
            .message(String.format(
                "Upstream circuit breaker OPENED for %s (%s) — blocking outbound calls for %ss, trip #%d",
                this.host, reason,
                remaining == null ? "?" : Long.toString(remaining.toSeconds()),
                this.breaker.tripCount()
            ))
            .eventCategory("network")
            .eventAction("circuit_breaker_trip")
            .eventOutcome("failure")
            .field("url.full", this.host)
            .field("event.reason", reason)
            .field("log.source", "application");
        if (status > 0) {
            log.field("http.response.status_code", status);
        }
        log.log();
        this.scheduleProbe();
    }

    /**
     * Success bookkeeping: closes the breaker when a block was set and
     * logs the recovery exactly once (INFO), including how many
     * requests fast-failed during the outage window.
     */
    private void onSuccess() {
        if (this.breaker.recordSuccess()) {
            final long failed = this.fastfails.getAndSet(0L);
            final long opened = this.openedAtMs.getAndSet(0L);
            final long openSeconds = opened == 0L
                ? -1L
                : Math.max(0L, (this.clock.millis() - opened) / 1000L);
            EcsLogger.info("com.auto1.pantera.http.client.circuitbreaker")
                .message(String.format(
                    "Upstream circuit breaker CLOSED for %s — open %ss, %d request(s) fast-failed while open",
                    this.host,
                    openSeconds < 0 ? "?" : Long.toString(openSeconds),
                    failed
                ))
                .eventCategory("network")
                .eventAction("circuit_breaker_close")
                .eventOutcome("success")
                .field("url.full", this.host)
                .field("log.source", "application")
                .log();
        }
    }

    /**
     * Synthesise a 502 carrying {@code X-Pantera-Circuit-Open: true}
     * and a best-effort {@code Retry-After} (seconds, rounded up).
     */
    private static Response synthesise502(final Duration remaining) {
        final ResponseBuilder builder = ResponseBuilder.from(RsStatus.BAD_GATEWAY)
            .header(CIRCUIT_OPEN_HEADER, "true")
            .textBody("Upstream circuit breaker is open");
        if (remaining != null) {
            final long seconds = Math.max(
                1L,
                remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0)
            );
            builder.header("Retry-After", Long.toString(seconds));
        }
        return builder.build();
    }

    /**
     * Schedule one daemon HEAD probe at {@link UpstreamCircuitBreaker#blockedUntil()}.
     * Multiple concurrent trips only schedule one probe per window via
     * the {@code probeScheduled} CAS guard.
     */
    private void scheduleProbe() {
        final Instant when = this.breaker.blockedUntil();
        if (when == null) {
            return;
        }
        if (!this.probeScheduled.compareAndSet(false, true)) {
            return;
        }
        final long delayMs = Math.max(
            0L, Duration.between(this.clock.instant(), when).toMillis()
        );
        this.probeExecutor.schedule(this::runProbe, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Execute the HEAD probe. Goes through the wrapped slice directly
     * so the breaker's open-check does not block the recovery test.
     * On success the breaker resets; on failure another probe is
     * scheduled for the next backoff window.
     */
    private void runProbe() {
        try {
            this.delegate.response(PROBE_LINE, Headers.EMPTY, Content.EMPTY).whenComplete(
                (response, error) -> {
                    this.probeScheduled.set(false);
                    this.onProbeResult(response, error);
                }
            );
        } catch (final RuntimeException ex) {
            this.probeScheduled.set(false);
            this.breaker.probeFailed(ex);
            this.logProbeFailure("probe dispatch failed: " + ex.getClass().getSimpleName());
            this.scheduleProbe();
        }
    }

    /**
     * HEAD probe terminal state. A failed probe re-trips
     * unconditionally (the window gate does not apply to probes — the
     * breaker already convicted this upstream, and the probe is the
     * only traffic there is); anything else — including a 404, which
     * just means "the upstream is reachable but doesn't have /" —
     * closes the breaker.
     */
    private void onProbeResult(final Response response, final Throwable error) {
        if (error != null) {
            if (this.config.get().shouldTripOnException().test(error)) {
                this.breaker.probeFailed(error);
                this.logProbeFailure("exception: " + error.getClass().getSimpleName());
                this.scheduleProbe();
            } else {
                this.onSuccess();
            }
            return;
        }
        if (response == null) {
            this.onSuccess();
            return;
        }
        final int status = response.status().code();
        if (this.config.get().shouldTripOnStatus().test(status)) {
            this.breaker.probeFailed(status);
            this.logProbeFailure("upstream status " + status);
            this.scheduleProbe();
        } else {
            this.onSuccess();
        }
    }

    /**
     * WARN transition log for a failed recovery probe — the upstream
     * is still down and the block window just grew along the Fibonacci
     * schedule.
     *
     * @param reason Human-readable probe failure cause.
     */
    private void logProbeFailure(final String reason) {
        final Duration remaining = this.breaker.timeRemaining();
        EcsLogger.warn("com.auto1.pantera.http.client.circuitbreaker")
            .message(String.format(
                "Upstream circuit breaker probe FAILED for %s (%s) — next probe in %ss",
                this.host, reason,
                remaining == null ? "?" : Long.toString(remaining.toSeconds())
            ))
            .eventCategory("network")
            .eventAction("circuit_breaker_probe")
            .eventOutcome("failure")
            .field("url.full", this.host)
            .field("event.reason", reason)
            .field("log.source", "application")
            .log();
    }

    /**
     * @return The host this decorator protects. Diagnostics only.
     */
    public String host() {
        return this.host;
    }
}
