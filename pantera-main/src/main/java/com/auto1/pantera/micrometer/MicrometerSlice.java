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
package com.auto1.pantera.micrometer;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vertx.micrometer.backends.BackendRegistries;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Calculated uploaded and downloaded body size for all requests.
 */
public final class MicrometerSlice implements Slice {

    /**
     * Tag method.
     */
    private static final String METHOD = "method";

    /**
     * Summary unit.
     */
    private static final String BYTES = "bytes";

    /**
     * Tag response status.
     */
    private static final String STATUS = "status";

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Micrometer registry.
     */
    private final MeterRegistry registry;

    /**
     * Update traffic metrics on requests and responses.
     * @param origin Origin slice to decorate
     */
    public MicrometerSlice(final Slice origin) {
        this(origin, BackendRegistries.getDefaultNow());
    }

    /**
     * Ctor.
     * @param origin Origin slice to decorate
     * @param registry Micrometer registry
     */
    public MicrometerSlice(final Slice origin, final MeterRegistry registry) {
        this.origin = origin;
        this.registry = registry;
    }

    @Override
    public CompletableFuture<Response> response(final RequestLine line, final Headers head,
                                                final Content body) {
        final String method = line.method().value();
        final long startTime = System.currentTimeMillis();
        final Counter.Builder requestCounter = Counter.builder("pantera.request.counter")
            .description("HTTP requests counter")
            .tag(MicrometerSlice.METHOD, method);
        final DistributionSummary requestBody = DistributionSummary.builder("pantera.request.body.size")
            .description("Request body size and chunks")
            .baseUnit(MicrometerSlice.BYTES)
            .tag(MicrometerSlice.METHOD, method)
            .register(this.registry);
        final DistributionSummary responseBody = DistributionSummary.builder("pantera.response.body.size")
            .baseUnit(MicrometerSlice.BYTES)
            .description("Response body size and chunks")
            .tag(MicrometerSlice.METHOD, method)
            .register(this.registry);
        final Timer.Sample timer = Timer.start(this.registry);

        return this.origin.response(line, head, new MicrometerPublisher(body, requestBody))
            .thenCompose(response -> {
                requestCounter.tag(MicrometerSlice.STATUS, response.status().name())
                    .register(MicrometerSlice.this.registry).increment();
                // CRITICAL FIX: Do NOT wrap response body with MicrometerPublisher.
                // Response bodies from storage are often Content.OneTime which can only
                // be subscribed once. Wrapping causes double subscription: once by
                // MicrometerPublisher for metrics, once by Vert.x for sending response.
                // Use Content-Length header for response size tracking instead.
                response.headers().values("Content-Length").stream()
                    .findFirst()
                    .ifPresent(contentLength -> {
                        try {
                            responseBody.record(Long.parseLong(contentLength));
                        } catch (final NumberFormatException ex) {
                            EcsLogger.debug("com.auto1.pantera.metrics")
                                .message("Invalid Content-Length header value")
                                .error(ex)
                                .log();
                        }
                    });
                // Pass response through unchanged - no body wrapping
                return CompletableFuture.completedFuture(response);
            }).handle(
                (resp, err) -> {
                    CompletableFuture<Response> res;
                    String name = "pantera.slice.response";
                    if (err != null) {
                        name = String.format("%s.error", name);
                        timer.stop(this.registry.timer(name));
                        res = CompletableFuture.failedFuture(err);
                    } else {
                        final long duration = System.currentTimeMillis() - startTime;
                        timer.stop(this.registry.timer(name, MicrometerSlice.STATUS, resp.status().name()));

                        // Record HTTP request metrics via MicrometerMetrics
                        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                            com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordHttpRequest(
                                method,
                                String.valueOf(resp.status().code()),
                                duration
                            );
                        }

                        res = CompletableFuture.completedFuture(resp);
                    }
                    return res;
                }
            ).thenCompose(Function.identity());
    }
}
