/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Metrics for GroupSlice operations.
 * Provides observability into group repository performance and behavior.
 * 
 * @since 1.18.21
 */
public final class GroupSliceMetrics {

    /**
     * Shared metrics instance.
     */
    private static volatile GroupSliceMetrics INSTANCE;

    /**
     * Meter registry.
     */
    private final MeterRegistry registry;

    /**
     * Request counters by group name.
     */
    private final ConcurrentMap<String, Counter> requestCounters = new ConcurrentHashMap<>();

    /**
     * Success counters by group name.
     */
    private final ConcurrentMap<String, Counter> successCounters = new ConcurrentHashMap<>();

    /**
     * Member hit counters by group and member name.
     */
    private final ConcurrentMap<String, Counter> memberHitCounters = new ConcurrentHashMap<>();

    /**
     * Response time timers by group name.
     */
    private final ConcurrentMap<String, Timer> responseTimers = new ConcurrentHashMap<>();

    /**
     * Batch size counters.
     */
    private final ConcurrentMap<String, Counter> batchSizeCounters = new ConcurrentHashMap<>();

    /**
     * Ctor.
     * @param registry Meter registry
     */
    private GroupSliceMetrics(final MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Initialize metrics with registry.
     * @param registry Meter registry
     */
    public static void initialize(final MeterRegistry registry) {
        if (INSTANCE == null) {
            synchronized (GroupSliceMetrics.class) {
                if (INSTANCE == null) {
                    INSTANCE = new GroupSliceMetrics(registry);
                }
            }
        }
    }

    /**
     * Get metrics instance.
     * @return Metrics instance or null if not initialized
     */
    public static GroupSliceMetrics instance() {
        return INSTANCE;
    }

    /**
     * Record a request to a group repository.
     * @param groupName Group repository name
     */
    public void recordRequest(final String groupName) {
        this.requestCounters.computeIfAbsent(
            groupName,
            name -> Counter.builder("artipie.group.requests")
                .description("Total requests to group repository")
                .tag("group", name)
                .register(this.registry)
        ).increment();
    }

    /**
     * Record a successful response from a group repository.
     * @param groupName Group repository name
     * @param memberName Member that provided the response
     */
    public void recordSuccess(final String groupName, final String memberName) {
        this.successCounters.computeIfAbsent(
            groupName,
            name -> Counter.builder("artipie.group.successes")
                .description("Successful responses from group repository")
                .tag("group", name)
                .register(this.registry)
        ).increment();

        this.memberHitCounters.computeIfAbsent(
            groupName + ":" + memberName,
            key -> Counter.builder("artipie.group.member.hits")
                .description("Successful responses by member repository")
                .tag("group", groupName)
                .tag("member", memberName)
                .register(this.registry)
        ).increment();
    }

    /**
     * Record batch processing metrics.
     * @param groupName Group repository name
     * @param batchSize Number of members in batch
     * @param duration Processing duration in milliseconds
     */
    public void recordBatch(final String groupName, final int batchSize, final long duration) {
        this.responseTimers.computeIfAbsent(
            groupName,
            name -> Timer.builder("artipie.group.response.time")
                .description("Group repository response time")
                .tag("group", name)
                .register(this.registry)
        ).record(duration, java.util.concurrent.TimeUnit.MILLISECONDS);

        this.batchSizeCounters.computeIfAbsent(
            groupName + ":" + batchSize,
            key -> Counter.builder("artipie.group.batch.size")
                .description("Batch sizes processed by group repository")
                .tag("group", groupName)
                .tag("size", String.valueOf(batchSize))
                .register(this.registry)
        ).increment();
    }

    /**
     * Record a 404 (not found) response.
     * @param groupName Group repository name
     */
    public void recordNotFound(final String groupName) {
        Counter.builder("artipie.group.not_found")
            .description("404 responses from group repository")
            .tag("group", groupName)
            .register(this.registry)
            .increment();
    }

    /**
     * Record an error during group processing.
     * @param groupName Group repository name
     * @param errorType Type of error (e.g., "timeout", "connection", "unknown")
     */
    public void recordError(final String groupName, final String errorType) {
        Counter.builder("artipie.group.errors")
            .description("Errors during group repository processing")
            .tag("group", groupName)
            .tag("error_type", errorType)
            .register(this.registry)
            .increment();
    }
}