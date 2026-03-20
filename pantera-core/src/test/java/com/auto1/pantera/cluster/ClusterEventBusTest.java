/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cluster;

import com.auto1.pantera.cache.ValkeyConnection;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Tests for {@link ClusterEventBus}.
 * <p>
 * Tests that do not require a running Valkey/Redis server verify
 * construction expectations and channel naming conventions.
 * Integration tests that require a real server are gated by the
 * VALKEY_HOST environment variable.
 *
 * @since 1.20.13
 */
final class ClusterEventBusTest {

    @Test
    void channelPrefixIsConsistent() {
        Assertions.assertEquals(
            "pantera:events:",
            ClusterEventBus.CHANNEL_PREFIX,
            "Channel prefix must follow the pantera:events: convention"
        );
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void createsAndClosesEventBus() throws Exception {
        final String host = System.getenv("VALKEY_HOST");
        final int port = Integer.parseInt(
            System.getenv().getOrDefault("VALKEY_PORT", "6379")
        );
        try (ValkeyConnection conn = new ValkeyConnection(
            host, port, Duration.ofSeconds(2)
        )) {
            try (ClusterEventBus bus = new ClusterEventBus(conn)) {
                Assertions.assertNotNull(
                    bus.instanceId(),
                    "Instance ID should be non-null"
                );
                Assertions.assertFalse(
                    bus.instanceId().isEmpty(),
                    "Instance ID should not be empty"
                );
                Assertions.assertEquals(
                    0, bus.topicCount(),
                    "No topics should be subscribed initially"
                );
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void subscribesAndCountsTopics() throws Exception {
        final String host = System.getenv("VALKEY_HOST");
        final int port = Integer.parseInt(
            System.getenv().getOrDefault("VALKEY_PORT", "6379")
        );
        try (ValkeyConnection conn = new ValkeyConnection(
            host, port, Duration.ofSeconds(2)
        )) {
            try (ClusterEventBus bus = new ClusterEventBus(conn)) {
                bus.subscribe("test.topic1", payload -> { });
                bus.subscribe("test.topic2", payload -> { });
                Assertions.assertEquals(
                    2, bus.topicCount(),
                    "Should track two subscribed topics"
                );
                bus.subscribe("test.topic1", payload -> { });
                Assertions.assertEquals(
                    2, bus.topicCount(),
                    "Adding second handler to same topic should not increase count"
                );
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void publishAndReceiveAcrossInstances() throws Exception {
        final String host = System.getenv("VALKEY_HOST");
        final int port = Integer.parseInt(
            System.getenv().getOrDefault("VALKEY_PORT", "6379")
        );
        try (ValkeyConnection conn = new ValkeyConnection(
            host, port, Duration.ofSeconds(2)
        )) {
            final CopyOnWriteArrayList<String> received =
                new CopyOnWriteArrayList<>();
            final CountDownLatch latch = new CountDownLatch(1);
            try (ClusterEventBus bus1 = new ClusterEventBus(conn);
                ClusterEventBus bus2 = new ClusterEventBus(conn)) {
                bus2.subscribe("cross.test", payload -> {
                    received.add(payload);
                    latch.countDown();
                });
                Thread.sleep(200);
                bus1.publish("cross.test", "{\"action\":\"test\"}");
                final boolean arrived = latch.await(5, TimeUnit.SECONDS);
                Assertions.assertTrue(
                    arrived,
                    "Message should arrive at second bus instance"
                );
                Assertions.assertEquals(
                    1, received.size(),
                    "Exactly one message should be received"
                );
                Assertions.assertEquals(
                    "{\"action\":\"test\"}", received.get(0),
                    "Payload should match what was published"
                );
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void selfPublishedMessagesAreIgnored() throws Exception {
        final String host = System.getenv("VALKEY_HOST");
        final int port = Integer.parseInt(
            System.getenv().getOrDefault("VALKEY_PORT", "6379")
        );
        try (ValkeyConnection conn = new ValkeyConnection(
            host, port, Duration.ofSeconds(2)
        )) {
            final CopyOnWriteArrayList<String> received =
                new CopyOnWriteArrayList<>();
            try (ClusterEventBus bus = new ClusterEventBus(conn)) {
                bus.subscribe("self.test", received::add);
                Thread.sleep(200);
                bus.publish("self.test", "should-not-arrive");
                Thread.sleep(1000);
                Assertions.assertTrue(
                    received.isEmpty(),
                    "Self-published messages should be filtered out"
                );
            }
        }
    }
}
