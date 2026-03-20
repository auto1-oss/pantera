/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Tests for {@link HttpClientSettings} defaults and factory methods.
 */
final class HttpClientSettingsTest {

    @Test
    @DisplayName("Default settings have non-zero idle timeout to prevent connection accumulation")
    void defaultIdleTimeoutIsNonZero() {
        final HttpClientSettings settings = new HttpClientSettings();
        assertThat(
            "Idle timeout should be non-zero to prevent connection accumulation",
            settings.idleTimeout(),
            greaterThan(0L)
        );
        assertThat(
            "Default idle timeout should be 30 seconds",
            settings.idleTimeout(),
            equalTo(HttpClientSettings.DEFAULT_IDLE_TIMEOUT)
        );
    }

    @Test
    @DisplayName("Default connection acquire timeout is reasonable (not 2 minutes)")
    void defaultConnectionAcquireTimeoutIsReasonable() {
        final HttpClientSettings settings = new HttpClientSettings();
        assertThat(
            "Connection acquire timeout should be 30 seconds or less",
            settings.connectionAcquireTimeout(),
            lessThanOrEqualTo(30_000L)
        );
    }

    @Test
    @DisplayName("Default max connections per destination is balanced")
    void defaultMaxConnectionsIsBalanced() {
        final HttpClientSettings settings = new HttpClientSettings();
        assertThat(
            "Max connections should be 64 for balanced resource usage",
            settings.maxConnectionsPerDestination(),
            equalTo(HttpClientSettings.DEFAULT_MAX_CONNECTIONS_PER_DESTINATION)
        );
    }

    @Test
    @DisplayName("Default max queued requests prevents unbounded queuing")
    void defaultMaxQueuedRequestsIsBounded() {
        final HttpClientSettings settings = new HttpClientSettings();
        assertThat(
            "Max queued requests should be 256 to prevent unbounded queuing",
            settings.maxRequestsQueuedPerDestination(),
            equalTo(HttpClientSettings.DEFAULT_MAX_REQUESTS_QUEUED_PER_DESTINATION)
        );
    }

    @Test
    @DisplayName("High throughput settings increase connection limits")
    void highThroughputSettingsIncreaseConnectionLimits() {
        final HttpClientSettings settings = HttpClientSettings.forHighThroughput();
        assertThat(
            "High throughput should have 128 max connections",
            settings.maxConnectionsPerDestination(),
            equalTo(128)
        );
        assertThat(
            "High throughput should have 512 max queued requests",
            settings.maxRequestsQueuedPerDestination(),
            equalTo(512)
        );
    }

    @Test
    @DisplayName("Many upstreams settings are conservative")
    void manyUpstreamsSettingsAreConservative() {
        final HttpClientSettings settings = HttpClientSettings.forManyUpstreams();
        assertThat(
            "Many upstreams should have 32 max connections",
            settings.maxConnectionsPerDestination(),
            equalTo(32)
        );
        assertThat(
            "Many upstreams should have 128 max queued requests",
            settings.maxRequestsQueuedPerDestination(),
            equalTo(128)
        );
        assertThat(
            "Many upstreams should have 15 second idle timeout",
            settings.idleTimeout(),
            equalTo(15_000L)
        );
    }

    @Test
    @DisplayName("Settings can be customized via setters")
    void settingsCanBeCustomized() {
        final HttpClientSettings settings = new HttpClientSettings()
            .setIdleTimeout(60_000L)
            .setConnectionAcquireTimeout(45_000L)
            .setMaxConnectionsPerDestination(256)
            .setMaxRequestsQueuedPerDestination(1024);

        assertThat(settings.idleTimeout(), equalTo(60_000L));
        assertThat(settings.connectionAcquireTimeout(), equalTo(45_000L));
        assertThat(settings.maxConnectionsPerDestination(), equalTo(256));
        assertThat(settings.maxRequestsQueuedPerDestination(), equalTo(1024));
    }

    @Test
    @DisplayName("Connect timeout default is 15 seconds")
    void connectTimeoutDefaultIs15Seconds() {
        final HttpClientSettings settings = new HttpClientSettings();
        assertThat(
            "Connect timeout should be 15 seconds",
            settings.connectTimeout(),
            equalTo(15_000L)
        );
    }

    @Test
    @DisplayName("Proxy timeout default is 60 seconds")
    void proxyTimeoutDefaultIs60Seconds() {
        final HttpClientSettings settings = new HttpClientSettings();
        assertThat(
            "Proxy timeout should be 60 seconds",
            settings.proxyTimeout(),
            equalTo(60L)
        );
    }

    @Test
    @DisplayName("Default constants are exposed for documentation")
    void defaultConstantsAreExposed() {
        assertThat(HttpClientSettings.DEFAULT_CONNECT_TIMEOUT, equalTo(15_000L));
        assertThat(HttpClientSettings.DEFAULT_IDLE_TIMEOUT, equalTo(30_000L));
        assertThat(HttpClientSettings.DEFAULT_CONNECTION_ACQUIRE_TIMEOUT, equalTo(30_000L));
        assertThat(HttpClientSettings.DEFAULT_MAX_CONNECTIONS_PER_DESTINATION, equalTo(64));
        assertThat(HttpClientSettings.DEFAULT_MAX_REQUESTS_QUEUED_PER_DESTINATION, equalTo(256));
        assertThat(HttpClientSettings.DEFAULT_PROXY_TIMEOUT, equalTo(60L));
    }

    @Test
    @DisplayName("Default Jetty buffer pool settings match constants")
    void defaultJettyBufferPoolSettings() {
        final HttpClientSettings settings = new HttpClientSettings();
        assertThat(
            "Default bucket size should be 1024",
            settings.jettyBucketSize(),
            equalTo(HttpClientSettings.DEFAULT_JETTY_BUCKET_SIZE)
        );
        assertThat(
            "Default direct memory should be 2 GB",
            settings.jettyDirectMemory(),
            equalTo(HttpClientSettings.DEFAULT_JETTY_DIRECT_MEMORY)
        );
        assertThat(
            "Default heap memory should be 1 GB",
            settings.jettyHeapMemory(),
            equalTo(HttpClientSettings.DEFAULT_JETTY_HEAP_MEMORY)
        );
    }

    @Test
    @DisplayName("Jetty buffer pool settings can be customized via fluent setters")
    void jettyBufferPoolCanBeCustomized() {
        final HttpClientSettings settings = new HttpClientSettings()
            .setJettyBucketSize(512)
            .setJettyDirectMemory(4L * 1024L * 1024L * 1024L)
            .setJettyHeapMemory(2L * 1024L * 1024L * 1024L);
        assertThat(settings.jettyBucketSize(), equalTo(512));
        assertThat(settings.jettyDirectMemory(), equalTo(4L * 1024L * 1024L * 1024L));
        assertThat(settings.jettyHeapMemory(), equalTo(2L * 1024L * 1024L * 1024L));
    }
}
