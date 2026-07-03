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
package com.auto1.pantera.http.resilience;

import java.time.Duration;

/**
 * Configuration limits for a {@link RepoBulkhead}.
 *
 * <p>Bindable from YAML per-repo configuration. Each repository may override
 * these defaults in {@code pantera.yml} under
 * {@code meta.repositories.<name>.bulkhead}.
 *
 * @param maxConcurrent Maximum number of concurrent in-flight requests
 *                      the bulkhead will admit before rejecting with
 *                      {@link com.auto1.pantera.http.fault.Fault.Overload}.
 * @param maxQueueDepth Maximum queue depth for the per-repo drain pool.
 *                      Drain tasks exceeding this depth are dropped with a
 *                      WARN log and a metrics counter increment.
 * @param retryAfter    Suggested duration for the {@code Retry-After} header
 *                      sent to clients when the bulkhead rejects a request.
 * @since 2.2.0
 */
public record BulkheadLimits(int maxConcurrent, int maxQueueDepth, Duration retryAfter) {

    /**
     * Canonical constructor with validation.
     */
    public BulkheadLimits {
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException(
                "maxConcurrent must be strictly positive: " + maxConcurrent
            );
        }
        if (maxQueueDepth <= 0) {
            throw new IllegalArgumentException(
                "maxQueueDepth must be strictly positive: " + maxQueueDepth
            );
        }
        if (retryAfter == null || retryAfter.isNegative() || retryAfter.isZero()) {
            throw new IllegalArgumentException(
                "retryAfter must be strictly positive: " + retryAfter
            );
        }
    }

    /**
     * Reasonable defaults: 200 concurrent requests, 1000-deep drain queue,
     * 1-second retry-after.
     *
     * @return Default limits suitable for most repositories.
     */
    public static BulkheadLimits defaults() {
        return new BulkheadLimits(200, 1000, Duration.ofSeconds(1));
    }
}
