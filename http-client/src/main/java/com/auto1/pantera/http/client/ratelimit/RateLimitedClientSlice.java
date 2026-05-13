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
package com.auto1.pantera.http.client.ratelimit;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.metrics.MicrometerMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Outbound rate-limit decorator. Wraps any client-side {@link Slice} —
 * placed by {@link com.auto1.pantera.http.client.jetty.JettyClientSlices}
 * around every per-host Jetty slice — and enforces a per-host token
 * bucket plus a 429 / Retry-After gate.
 *
 * <p>Decision flow per outbound call:
 * <ol>
 *   <li>If the per-host gate is closed: synthesise a local 429 response
 *       carrying a {@code Retry-After} header pointing at the gate
 *       deadline. The Jetty layer is never invoked, so the outbound
 *       request never leaves Pantera — the upstream sees no traffic
 *       while gated.</li>
 *   <li>Otherwise, attempt to acquire a token. On success, delegate
 *       through to the wrapped slice. On failure (bucket empty),
 *       synthesise a 429 with a one-second Retry-After. The bucket
 *       refills continuously, so the next attempt has a token within a
 *       fraction of a second.</li>
 *   <li>When the wrapped slice completes, inspect the response status.
 *       A 429 (or 503 with Retry-After) closes the gate via
 *       {@link UpstreamRateLimiter#recordResponse(String, int, Duration)}
 *       so subsequent acquires fail-fast for the gate duration.</li>
 * </ol>
 *
 * <p>The synthesised 429 carries header {@code X-Pantera-Rate-Limited: true}
 * so callers (BaseCachedProxySlice, future cluster-wide propagation) can
 * distinguish self-imposed gating from upstream-imposed 429.
 *
 * @since 2.2.0
 */
public final class RateLimitedClientSlice implements Slice {

    /** Header marker: response was generated locally by the rate limiter. */
    public static final String PANTERA_LIMITED_HEADER = "X-Pantera-Rate-Limited";

    /** Default Retry-After when the bucket is empty (no upstream gate active). */
    private static final Duration BUCKET_EMPTY_RETRY_AFTER = Duration.ofSeconds(1);

    private final Slice delegate;
    private final String host;
    private final UpstreamRateLimiter limiter;
    private final Clock clock;

    public RateLimitedClientSlice(
        final Slice delegate,
        final String host,
        final UpstreamRateLimiter limiter,
        final Clock clock
    ) {
        this.delegate = delegate;
        this.host = host;
        this.limiter = limiter;
        this.clock = clock;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final Instant gateUntil = this.limiter.gateOpenUntil(this.host);
        if (gateUntil != null) {
            recordRateLimited("gate_closed");
            return CompletableFuture.completedFuture(
                synthesise429(Duration.between(this.clock.instant(), gateUntil))
            );
        }
        if (!this.limiter.tryAcquire(this.host)) {
            recordRateLimited("bucket_empty");
            return CompletableFuture.completedFuture(synthesise429(BUCKET_EMPTY_RETRY_AFTER));
        }
        return this.delegate.response(line, headers, body).thenApply(
            response -> {
                if (response != null) {
                    final int status = response.status().code();
                    if (status == 429 || status == 503) {
                        final java.util.List<String> raw =
                            response.headers().values("Retry-After");
                        final Duration retryAfter = RetryAfter.parse(
                            raw.isEmpty() ? null : raw.get(0), this.clock
                        );
                        this.limiter.recordResponse(this.host, status, retryAfter);
                    }
                }
                return response;
            }
        );
    }

    private Response synthesise429(final Duration retryAfter) {
        final long seconds = Math.max(1L, retryAfter.getSeconds() + (retryAfter.getNano() > 0 ? 1 : 0));
        return ResponseBuilder.from(RsStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", Long.toString(seconds))
            .header(PANTERA_LIMITED_HEADER, "true")
            .build();
    }

    private void recordRateLimited(final String reason) {
        if (!MicrometerMetrics.isInitialized()) {
            return;
        }
        MicrometerMetrics.getInstance().recordOutboundRateLimited(this.host, reason);
    }
}
