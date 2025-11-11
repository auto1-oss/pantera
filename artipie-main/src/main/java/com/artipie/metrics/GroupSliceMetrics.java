/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.metrics;

/**
 * GroupSlice metrics - Compatibility wrapper for OpenTelemetry.
 * Delegates to OtelMetrics for backward compatibility.
 * 
 * @deprecated Use {@link com.artipie.metrics.otel.OtelMetrics} directly
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
    
    // Delegate to OtelMetrics
    
    public void recordRequest(final String groupName) {
        if (com.artipie.metrics.otel.OtelMetrics.isInitialized()) {
            com.artipie.metrics.otel.OtelMetrics.get().recordGroupRequest(groupName);
        }
    }
    
    public void recordSuccess(final String groupName, final String memberName) {
        if (com.artipie.metrics.otel.OtelMetrics.isInitialized()) {
            com.artipie.metrics.otel.OtelMetrics.get().recordGroupMemberRequest(
                groupName, memberName, "success"
            );
        }
    }
    
    public void recordBatch(final String groupName, final int batchSize, final long duration) {
        if (com.artipie.metrics.otel.OtelMetrics.isInitialized()) {
            com.artipie.metrics.otel.OtelMetrics.get().recordGroupResolution(groupName, duration);
        }
    }
    
    public void recordNotFound(final String groupName) {
        if (com.artipie.metrics.otel.OtelMetrics.isInitialized()) {
            com.artipie.metrics.otel.OtelMetrics.get().recordGroupMemberRequest(
                groupName, "none", "not_found"
            );
        }
    }
    
    public void recordError(final String groupName, final String errorType) {
        // Errors tracked separately in OpenTelemetry
    }
}
