/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.proxy;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Configuration for enterprise proxy features.
 * Provides centralized settings for retry, backpressure, auto-block,
 * and request deduplication.
 *
 * <p>Example YAML configuration:</p>
 * <pre>{@code
 * proxy:
 *   request_timeout: 90s
 *   retry:
 *     enabled: true
 *     max_attempts: 3
 *     initial_delay: 100ms
 *     max_delay: 10s
 *     multiplier: 2.0
 *   backpressure:
 *     enabled: true
 *     max_concurrent: 50
 *     queue_timeout: 30s
 *   auto_block:
 *     enabled: true
 *     failure_threshold: 5
 *     window: 1m
 *     block_duration: 5m
 *   deduplication:
 *     enabled: true
 *     distributed: auto  # auto, local, valkey
 * }</pre>
 *
 * @since 1.0
 */
public final class ProxyConfig {

    /**
     * Default configuration with all features enabled.
     */
    public static final ProxyConfig DEFAULT = ProxyConfig.builder().build();

    /**
     * Minimal configuration with only essential features.
     */
    public static final ProxyConfig MINIMAL = ProxyConfig.builder()
        .retryEnabled(false)
        .backpressureEnabled(false)
        .autoBlockEnabled(false)
        .build();

    /**
     * Request timeout for upstream calls.
     */
    private final Duration requestTimeout;

    /**
     * Retry configuration.
     */
    private final RetryConfig retry;

    /**
     * Backpressure configuration.
     */
    private final BackpressureConfig backpressure;

    /**
     * Auto-block configuration.
     */
    private final AutoBlockConfig autoBlock;

    /**
     * Deduplication configuration.
     */
    private final DeduplicationConfig deduplication;

    private ProxyConfig(final Builder builder) {
        this.requestTimeout = builder.requestTimeout;
        this.retry = builder.buildRetryConfig();
        this.backpressure = builder.buildBackpressureConfig();
        this.autoBlock = builder.buildAutoBlockConfig();
        this.deduplication = builder.buildDeduplicationConfig();
    }

    /**
     * Create a new builder.
     *
     * @return Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Get request timeout.
     *
     * @return Request timeout
     */
    public Duration requestTimeout() {
        return this.requestTimeout;
    }

    /**
     * Get retry configuration.
     *
     * @return Retry config
     */
    public RetryConfig retry() {
        return this.retry;
    }

    /**
     * Get backpressure configuration.
     *
     * @return Backpressure config
     */
    public BackpressureConfig backpressure() {
        return this.backpressure;
    }

    /**
     * Get auto-block configuration.
     *
     * @return Auto-block config
     */
    public AutoBlockConfig autoBlock() {
        return this.autoBlock;
    }

    /**
     * Get deduplication configuration.
     *
     * @return Deduplication config
     */
    public DeduplicationConfig deduplication() {
        return this.deduplication;
    }

    /**
     * Build RetryPolicy from this configuration.
     *
     * @return RetryPolicy or NO_RETRY if disabled
     */
    public RetryPolicy buildRetryPolicy() {
        if (!this.retry.enabled) {
            return RetryPolicy.NO_RETRY;
        }
        return RetryPolicy.builder()
            .maxAttempts(this.retry.maxAttempts)
            .initialDelay(this.retry.initialDelay)
            .maxDelay(this.retry.maxDelay)
            .multiplier(this.retry.multiplier)
            .jitterFactor(this.retry.jitterFactor)
            .build();
    }

    /**
     * Build BackpressureController from this configuration.
     *
     * @param name Controller name for metrics
     * @return BackpressureController or null if disabled
     */
    public Optional<BackpressureController> buildBackpressureController(final String name) {
        if (!this.backpressure.enabled) {
            return Optional.empty();
        }
        return Optional.of(new BackpressureController(
            this.backpressure.maxConcurrent,
            this.backpressure.queueTimeout,
            name
        ));
    }

    /**
     * Build AutoBlockService from this configuration.
     *
     * @return AutoBlockService or null if disabled
     */
    public Optional<AutoBlockService> buildAutoBlockService() {
        if (!this.autoBlock.enabled) {
            return Optional.empty();
        }
        return Optional.of(new AutoBlockService(
            this.autoBlock.failureThreshold,
            this.autoBlock.window,
            this.autoBlock.blockDuration
        ));
    }

    /**
     * Retry configuration.
     */
    public static final class RetryConfig {
        private final boolean enabled;
        private final int maxAttempts;
        private final Duration initialDelay;
        private final Duration maxDelay;
        private final double multiplier;
        private final double jitterFactor;

        RetryConfig(
            final boolean enabled,
            final int maxAttempts,
            final Duration initialDelay,
            final Duration maxDelay,
            final double multiplier,
            final double jitterFactor
        ) {
            this.enabled = enabled;
            this.maxAttempts = maxAttempts;
            this.initialDelay = initialDelay;
            this.maxDelay = maxDelay;
            this.multiplier = multiplier;
            this.jitterFactor = jitterFactor;
        }

        public boolean enabled() {
            return this.enabled;
        }

        public int maxAttempts() {
            return this.maxAttempts;
        }

        public Duration initialDelay() {
            return this.initialDelay;
        }

        public Duration maxDelay() {
            return this.maxDelay;
        }

        public double multiplier() {
            return this.multiplier;
        }

        public double jitterFactor() {
            return this.jitterFactor;
        }
    }

    /**
     * Backpressure configuration.
     */
    public static final class BackpressureConfig {
        private final boolean enabled;
        private final int maxConcurrent;
        private final Duration queueTimeout;

        BackpressureConfig(
            final boolean enabled,
            final int maxConcurrent,
            final Duration queueTimeout
        ) {
            this.enabled = enabled;
            this.maxConcurrent = maxConcurrent;
            this.queueTimeout = queueTimeout;
        }

        public boolean enabled() {
            return this.enabled;
        }

        public int maxConcurrent() {
            return this.maxConcurrent;
        }

        public Duration queueTimeout() {
            return this.queueTimeout;
        }
    }

    /**
     * Auto-block configuration.
     */
    public static final class AutoBlockConfig {
        private final boolean enabled;
        private final int failureThreshold;
        private final Duration window;
        private final Duration blockDuration;

        AutoBlockConfig(
            final boolean enabled,
            final int failureThreshold,
            final Duration window,
            final Duration blockDuration
        ) {
            this.enabled = enabled;
            this.failureThreshold = failureThreshold;
            this.window = window;
            this.blockDuration = blockDuration;
        }

        public boolean enabled() {
            return this.enabled;
        }

        public int failureThreshold() {
            return this.failureThreshold;
        }

        public Duration window() {
            return this.window;
        }

        public Duration blockDuration() {
            return this.blockDuration;
        }
    }

    /**
     * Deduplication configuration.
     */
    public static final class DeduplicationConfig {
        /**
         * Deduplication mode.
         */
        public enum Mode {
            /**
             * Automatically choose based on Valkey availability.
             */
            AUTO,
            /**
             * Force local-only deduplication.
             */
            LOCAL,
            /**
             * Force distributed deduplication (requires Valkey).
             */
            VALKEY
        }

        private final boolean enabled;
        private final Mode mode;
        private final Duration timeout;

        DeduplicationConfig(
            final boolean enabled,
            final Mode mode,
            final Duration timeout
        ) {
            this.enabled = enabled;
            this.mode = mode;
            this.timeout = timeout;
        }

        public boolean enabled() {
            return this.enabled;
        }

        public Mode mode() {
            return this.mode;
        }

        public Duration timeout() {
            return this.timeout;
        }
    }

    /**
     * Builder for ProxyConfig.
     */
    public static final class Builder {
        private Duration requestTimeout = Duration.ofSeconds(90);
        private boolean retryEnabled = true;
        private int retryMaxAttempts = 3;
        private Duration retryInitialDelay = Duration.ofMillis(100);
        private Duration retryMaxDelay = Duration.ofSeconds(10);
        private double retryMultiplier = 2.0;
        private double retryJitterFactor = 0.25;
        private boolean backpressureEnabled = true;
        private int backpressureMaxConcurrent = 50;
        private Duration backpressureQueueTimeout = Duration.ofSeconds(30);
        private boolean autoBlockEnabled = true;
        private int autoBlockFailureThreshold = 5;
        private Duration autoBlockWindow = Duration.ofMinutes(1);
        private Duration autoBlockBlockDuration = Duration.ofMinutes(5);
        private boolean deduplicationEnabled = true;
        private DeduplicationConfig.Mode deduplicationMode = DeduplicationConfig.Mode.AUTO;
        private Duration deduplicationTimeout = Duration.ofSeconds(90);

        private Builder() {
        }

        public Builder requestTimeout(final Duration timeout) {
            this.requestTimeout = Objects.requireNonNull(timeout);
            return this;
        }

        public Builder retryEnabled(final boolean enabled) {
            this.retryEnabled = enabled;
            return this;
        }

        public Builder retryMaxAttempts(final int attempts) {
            this.retryMaxAttempts = attempts;
            return this;
        }

        public Builder retryInitialDelay(final Duration delay) {
            this.retryInitialDelay = Objects.requireNonNull(delay);
            return this;
        }

        public Builder retryMaxDelay(final Duration delay) {
            this.retryMaxDelay = Objects.requireNonNull(delay);
            return this;
        }

        public Builder retryMultiplier(final double multiplier) {
            this.retryMultiplier = multiplier;
            return this;
        }

        public Builder retryJitterFactor(final double factor) {
            this.retryJitterFactor = factor;
            return this;
        }

        public Builder backpressureEnabled(final boolean enabled) {
            this.backpressureEnabled = enabled;
            return this;
        }

        public Builder backpressureMaxConcurrent(final int max) {
            this.backpressureMaxConcurrent = max;
            return this;
        }

        public Builder backpressureQueueTimeout(final Duration timeout) {
            this.backpressureQueueTimeout = Objects.requireNonNull(timeout);
            return this;
        }

        public Builder autoBlockEnabled(final boolean enabled) {
            this.autoBlockEnabled = enabled;
            return this;
        }

        public Builder autoBlockFailureThreshold(final int threshold) {
            this.autoBlockFailureThreshold = threshold;
            return this;
        }

        public Builder autoBlockWindow(final Duration window) {
            this.autoBlockWindow = Objects.requireNonNull(window);
            return this;
        }

        public Builder autoBlockBlockDuration(final Duration duration) {
            this.autoBlockBlockDuration = Objects.requireNonNull(duration);
            return this;
        }

        public Builder deduplicationEnabled(final boolean enabled) {
            this.deduplicationEnabled = enabled;
            return this;
        }

        public Builder deduplicationMode(final DeduplicationConfig.Mode mode) {
            this.deduplicationMode = Objects.requireNonNull(mode);
            return this;
        }

        public Builder deduplicationTimeout(final Duration timeout) {
            this.deduplicationTimeout = Objects.requireNonNull(timeout);
            return this;
        }

        public ProxyConfig build() {
            return new ProxyConfig(this);
        }

        RetryConfig buildRetryConfig() {
            return new RetryConfig(
                this.retryEnabled,
                this.retryMaxAttempts,
                this.retryInitialDelay,
                this.retryMaxDelay,
                this.retryMultiplier,
                this.retryJitterFactor
            );
        }

        BackpressureConfig buildBackpressureConfig() {
            return new BackpressureConfig(
                this.backpressureEnabled,
                this.backpressureMaxConcurrent,
                this.backpressureQueueTimeout
            );
        }

        AutoBlockConfig buildAutoBlockConfig() {
            return new AutoBlockConfig(
                this.autoBlockEnabled,
                this.autoBlockFailureThreshold,
                this.autoBlockWindow,
                this.autoBlockBlockDuration
            );
        }

        DeduplicationConfig buildDeduplicationConfig() {
            return new DeduplicationConfig(
                this.deduplicationEnabled,
                this.deduplicationMode,
                this.deduplicationTimeout
            );
        }
    }
}
