/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ValkeyConnection} Pub/Sub functionality.
 * Requires Docker to be running.
 */
@Testcontainers
@EnabledIf("isDockerAvailable")
class ValkeyConnectionIT {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    private static ValkeyConnection connection;

    @BeforeAll
    static void setUp() {
        REDIS.start();
        connection = new ValkeyConnection(
            REDIS.getHost(),
            REDIS.getFirstMappedPort(),
            Duration.ofSeconds(5)
        );
    }

    @AfterAll
    static void tearDown() {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void subscriberReceivesPublishedMessage() throws Exception {
        final String channel = "test:channel:1";
        final String testMessage = "Hello, Pub/Sub!";
        final AtomicReference<String> receivedMessage = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);

        // Subscribe to channel
        connection.subscribe(channel, message -> {
            receivedMessage.set(message);
            latch.countDown();
        }).get(5, TimeUnit.SECONDS);

        // Give a moment for subscription to be established
        Thread.sleep(100);

        // Publish message
        final Long receivers = connection.publish(channel, testMessage)
            .get(5, TimeUnit.SECONDS);

        // Wait for message to be received
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive message within timeout");
        assertEquals(testMessage, receivedMessage.get(), "Should receive correct message");

        // Cleanup
        connection.unsubscribe(channel).get(5, TimeUnit.SECONDS);
    }

    @Test
    void multipleSubscribersReceiveMessages() throws Exception {
        final String channel1 = "test:channel:2a";
        final String channel2 = "test:channel:2b";
        final String message1 = "Message for channel 1";
        final String message2 = "Message for channel 2";
        final AtomicReference<String> received1 = new AtomicReference<>();
        final AtomicReference<String> received2 = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(2);

        // Subscribe to two channels
        connection.subscribe(channel1, msg -> {
            received1.set(msg);
            latch.countDown();
        }).get(5, TimeUnit.SECONDS);

        connection.subscribe(channel2, msg -> {
            received2.set(msg);
            latch.countDown();
        }).get(5, TimeUnit.SECONDS);

        Thread.sleep(100);

        // Publish to both channels
        connection.publish(channel1, message1).get(5, TimeUnit.SECONDS);
        connection.publish(channel2, message2).get(5, TimeUnit.SECONDS);

        // Wait for both messages
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive both messages");
        assertEquals(message1, received1.get(), "Channel 1 should receive correct message");
        assertEquals(message2, received2.get(), "Channel 2 should receive correct message");

        // Cleanup
        connection.unsubscribe(channel1).get(5, TimeUnit.SECONDS);
        connection.unsubscribe(channel2).get(5, TimeUnit.SECONDS);
    }

    @Test
    void unsubscribeStopsReceivingMessages() throws Exception {
        final String channel = "test:channel:3";
        final AtomicReference<String> receivedMessage = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);

        // Subscribe
        connection.subscribe(channel, message -> {
            receivedMessage.set(message);
            latch.countDown();
        }).get(5, TimeUnit.SECONDS);

        Thread.sleep(100);

        // Unsubscribe
        connection.unsubscribe(channel).get(5, TimeUnit.SECONDS);

        Thread.sleep(100);

        // Publish after unsubscribe
        connection.publish(channel, "This should not be received").get(5, TimeUnit.SECONDS);

        // Should not receive the message
        assertFalse(latch.await(500, TimeUnit.MILLISECONDS), "Should not receive message after unsubscribe");
        assertNull(receivedMessage.get(), "Should not have received any message");
    }

    @Test
    void asyncOperationsAreNonBlocking() throws Exception {
        final String channel = "test:channel:4";

        // These should complete quickly without blocking
        final long startTime = System.currentTimeMillis();

        final CompletableFuture<String> subscribeFuture = connection.subscribe(channel, msg -> {});
        final CompletableFuture<Long> publishFuture = connection.publish(channel, "test");
        final CompletableFuture<Void> unsubscribeFuture = connection.unsubscribe(channel);

        // All operations should be non-blocking
        final long initTime = System.currentTimeMillis() - startTime;
        assertTrue(initTime < 100, "Operations should be initiated quickly (non-blocking)");

        // Wait for all to complete
        CompletableFuture.allOf(subscribeFuture, publishFuture, unsubscribeFuture)
            .get(5, TimeUnit.SECONDS);
    }

    static boolean isDockerAvailable() {
        try {
            final Process process = Runtime.getRuntime().exec(new String[]{"docker", "info"});
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
