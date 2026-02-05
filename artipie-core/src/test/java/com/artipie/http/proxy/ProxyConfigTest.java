/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.proxy;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProxyConfig}.
 */
class ProxyConfigTest {

    @Test
    void defaultConfigHasAllFeaturesEnabled() {
        final ProxyConfig config = ProxyConfig.DEFAULT;

        assertTrue(config.retry().enabled());
        assertTrue(config.backpressure().enabled());
        assertTrue(config.autoBlock().enabled());
        assertTrue(config.deduplication().enabled());

        assertEquals(Duration.ofSeconds(90), config.requestTimeout());
    }

    @Test
    void minimalConfigDisablesOptionalFeatures() {
        final ProxyConfig config = ProxyConfig.MINIMAL;

        assertFalse(config.retry().enabled());
        assertFalse(config.backpressure().enabled());
        assertFalse(config.autoBlock().enabled());
        assertTrue(config.deduplication().enabled());  // Always enabled for safety
    }

    @Test
    void builderCreatesCustomConfig() {
        final ProxyConfig config = ProxyConfig.builder()
            .requestTimeout(Duration.ofSeconds(120))
            .retryMaxAttempts(5)
            .retryInitialDelay(Duration.ofMillis(200))
            .backpressureMaxConcurrent(100)
            .backpressureQueueTimeout(Duration.ofSeconds(60))
            .autoBlockFailureThreshold(10)
            .autoBlockWindow(Duration.ofMinutes(2))
            .autoBlockBlockDuration(Duration.ofMinutes(10))
            .deduplicationMode(ProxyConfig.DeduplicationConfig.Mode.LOCAL)
            .deduplicationTimeout(Duration.ofSeconds(120))
            .build();

        assertEquals(Duration.ofSeconds(120), config.requestTimeout());
        assertEquals(5, config.retry().maxAttempts());
        assertEquals(Duration.ofMillis(200), config.retry().initialDelay());
        assertEquals(100, config.backpressure().maxConcurrent());
        assertEquals(Duration.ofSeconds(60), config.backpressure().queueTimeout());
        assertEquals(10, config.autoBlock().failureThreshold());
        assertEquals(Duration.ofMinutes(2), config.autoBlock().window());
        assertEquals(Duration.ofMinutes(10), config.autoBlock().blockDuration());
        assertEquals(ProxyConfig.DeduplicationConfig.Mode.LOCAL, config.deduplication().mode());
        assertEquals(Duration.ofSeconds(120), config.deduplication().timeout());
    }

    @Test
    void buildRetryPolicyReturnsConfiguredPolicy() {
        final ProxyConfig config = ProxyConfig.builder()
            .retryMaxAttempts(5)
            .build();

        final RetryPolicy policy = config.buildRetryPolicy();
        assertNotNull(policy);
        assertEquals(5, policy.maxAttempts());
    }

    @Test
    void buildRetryPolicyReturnsNoRetryWhenDisabled() {
        final ProxyConfig config = ProxyConfig.builder()
            .retryEnabled(false)
            .build();

        final RetryPolicy policy = config.buildRetryPolicy();
        assertEquals(RetryPolicy.NO_RETRY, policy);
    }

    @Test
    void buildBackpressureControllerReturnsConfigured() {
        final ProxyConfig config = ProxyConfig.builder()
            .backpressureMaxConcurrent(50)
            .backpressureQueueTimeout(Duration.ofSeconds(30))
            .build();

        final var controller = config.buildBackpressureController("test-repo");
        assertTrue(controller.isPresent());
        assertEquals(50, controller.get().maxConcurrent());
        assertEquals("test-repo", controller.get().name());
    }

    @Test
    void buildBackpressureControllerReturnsEmptyWhenDisabled() {
        final ProxyConfig config = ProxyConfig.builder()
            .backpressureEnabled(false)
            .build();

        final var controller = config.buildBackpressureController("test-repo");
        assertTrue(controller.isEmpty());
    }

    @Test
    void buildAutoBlockServiceReturnsConfigured() {
        final ProxyConfig config = ProxyConfig.builder()
            .autoBlockFailureThreshold(5)
            .autoBlockWindow(Duration.ofMinutes(1))
            .autoBlockBlockDuration(Duration.ofMinutes(5))
            .build();

        final var service = config.buildAutoBlockService();
        assertTrue(service.isPresent());
    }

    @Test
    void buildAutoBlockServiceReturnsEmptyWhenDisabled() {
        final ProxyConfig config = ProxyConfig.builder()
            .autoBlockEnabled(false)
            .build();

        final var service = config.buildAutoBlockService();
        assertTrue(service.isEmpty());
    }

    @Test
    void retryConfigDefaults() {
        final ProxyConfig config = ProxyConfig.DEFAULT;

        assertEquals(3, config.retry().maxAttempts());
        assertEquals(Duration.ofMillis(100), config.retry().initialDelay());
        assertEquals(Duration.ofSeconds(10), config.retry().maxDelay());
        assertEquals(2.0, config.retry().multiplier(), 0.01);
        assertEquals(0.25, config.retry().jitterFactor(), 0.01);
    }

    @Test
    void backpressureConfigDefaults() {
        final ProxyConfig config = ProxyConfig.DEFAULT;

        assertEquals(50, config.backpressure().maxConcurrent());
        assertEquals(Duration.ofSeconds(30), config.backpressure().queueTimeout());
    }

    @Test
    void autoBlockConfigDefaults() {
        final ProxyConfig config = ProxyConfig.DEFAULT;

        assertEquals(5, config.autoBlock().failureThreshold());
        assertEquals(Duration.ofMinutes(1), config.autoBlock().window());
        assertEquals(Duration.ofMinutes(5), config.autoBlock().blockDuration());
    }

    @Test
    void deduplicationConfigDefaults() {
        final ProxyConfig config = ProxyConfig.DEFAULT;

        assertEquals(ProxyConfig.DeduplicationConfig.Mode.AUTO, config.deduplication().mode());
        assertEquals(Duration.ofSeconds(90), config.deduplication().timeout());
    }
}
