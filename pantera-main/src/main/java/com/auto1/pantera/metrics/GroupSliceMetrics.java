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
package com.auto1.pantera.metrics;

/**
 * GroupSlice metrics - Compatibility wrapper for Micrometer.
 * Delegates to MicrometerMetrics for backward compatibility.
 *
 * @deprecated Use {@link com.auto1.pantera.metrics.MicrometerMetrics} directly
 * @since 1.18.21
 */
@Deprecated
public final class GroupSliceMetrics {

    private static volatile GroupSliceMetrics INSTANCE;

    private GroupSliceMetrics() {
        // Private constructor
    }

    public static void initialize(final Object registry) {
        if (INSTANCE == null) {
            synchronized (GroupSliceMetrics.class) {
                if (INSTANCE == null) {
                    INSTANCE = new GroupSliceMetrics();
                }
            }
        }
    }

    public static GroupSliceMetrics instance() {
        return INSTANCE;
    }

    // Delegate to MicrometerMetrics

    public void recordRequest(final String groupName) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordGroupRequest(groupName, "success");
        }
    }

    public void recordSuccess(final String groupName, final String memberName, final long latencyMs) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordGroupMemberRequest(
                groupName, memberName, "success"
            );
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordGroupMemberLatency(
                groupName, memberName, "success", latencyMs
            );
        }
    }

    public void recordBatch(final String groupName, final int batchSize, final long duration) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordGroupResolutionDuration(groupName, duration);
        }
    }

    public void recordNotFound(final String groupName) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordGroupMemberRequest(
                groupName, "none", "not_found"
            );
        }
    }

    public void recordError(final String groupName, final String errorType) {
        // Errors tracked separately in Micrometer
    }

    /**
     * Increment the {@code pantera.group.drain.dropped} Micrometer counter.
     *
     * <p>Called from the per-repo drain executor rejection handler in
     * {@link com.auto1.pantera.http.resilience.RepoBulkhead} whenever a drain task is dropped
     * because the bounded queue is full.  Each increment represents one undrained
     * loser response body — a potential Jetty socket leak until idle-timeout.
     * Ops should alert on any sustained non-zero rate of this counter.
     */
    public void recordDrainDropped() {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            io.micrometer.core.instrument.Counter
                .builder("pantera.group.drain.dropped")
                .description(
                    "Response body drain tasks dropped due to saturated drain executor. "
                    + "Each drop = leaked Jetty connection until idle-timeout."
                )
                .register(
                    com.auto1.pantera.metrics.MicrometerMetrics.getInstance().getRegistry()
                )
                .increment();
        }
    }
}
