/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cache;

import java.time.Duration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Tests for {@link ValkeyConnection}.
 * Tests that do not require a running Valkey/Redis server verify
 * pool configuration and lifecycle. Tests that require a server
 * are gated by the VALKEY_HOST environment variable.
 */
final class ValkeyConnectionTest {

    @Test
    void failsToConnectToNonExistentHost() {
        Assertions.assertThrows(
            Exception.class,
            () -> new ValkeyConnection(
                "192.0.2.1",
                9999,
                Duration.ofMillis(200)
            ),
            "Should fail when Valkey/Redis is not available"
        );
    }

    @Test
    void failsToConnectWithCustomPoolSize() {
        Assertions.assertThrows(
            Exception.class,
            () -> new ValkeyConnection(
                "192.0.2.1",
                9999,
                Duration.ofMillis(200),
                4
            ),
            "Should fail with custom pool size when Valkey/Redis is not available"
        );
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void connectsAndClosesWithDefaultPoolSize() throws Exception {
        final String host = System.getenv("VALKEY_HOST");
        final int port = Integer.parseInt(
            System.getenv().getOrDefault("VALKEY_PORT", "6379")
        );
        try (ValkeyConnection conn = new ValkeyConnection(
            host, port, Duration.ofSeconds(2)
        )) {
            Assertions.assertEquals(
                8, conn.poolSize(),
                "Default pool size should be 8"
            );
            Assertions.assertNotNull(
                conn.async(),
                "async() should return non-null commands"
            );
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void connectsWithCustomPoolSizeAndPings() throws Exception {
        final String host = System.getenv("VALKEY_HOST");
        final int port = Integer.parseInt(
            System.getenv().getOrDefault("VALKEY_PORT", "6379")
        );
        try (ValkeyConnection conn = new ValkeyConnection(
            host, port, Duration.ofSeconds(2), 4
        )) {
            Assertions.assertEquals(
                4, conn.poolSize(),
                "Custom pool size should be 4"
            );
            Assertions.assertTrue(
                conn.pingAsync().get(),
                "pingAsync should return true when connected"
            );
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void roundRobinsAcrossConnections() throws Exception {
        final String host = System.getenv("VALKEY_HOST");
        final int port = Integer.parseInt(
            System.getenv().getOrDefault("VALKEY_PORT", "6379")
        );
        try (ValkeyConnection conn = new ValkeyConnection(
            host, port, Duration.ofSeconds(2), 3
        )) {
            final var first = conn.async();
            final var second = conn.async();
            final var third = conn.async();
            final var fourth = conn.async();
            Assertions.assertSame(
                first, fourth,
                "Fourth call should return same commands as first (round-robin)"
            );
            Assertions.assertNotSame(
                first, second,
                "Consecutive calls should return different commands objects"
            );
        }
    }
}
