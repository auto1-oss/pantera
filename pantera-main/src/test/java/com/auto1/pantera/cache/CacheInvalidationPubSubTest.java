/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cache;

import com.auto1.pantera.asto.misc.Cleanable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link CacheInvalidationPubSub}.
 * Uses a Testcontainers Valkey/Redis container.
 *
 * @since 1.20.13
 */
@Testcontainers
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
final class CacheInvalidationPubSubTest {

    /**
     * Valkey container.
     */
    @Container
    @SuppressWarnings("rawtypes")
    private static final GenericContainer VALKEY =
        new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    /**
     * First Valkey connection (simulates instance A).
     */
    private ValkeyConnection connA;

    /**
     * Second Valkey connection (simulates instance B).
     */
    private ValkeyConnection connB;

    /**
     * Pub/sub for instance A.
     */
    private CacheInvalidationPubSub pubsubA;

    /**
     * Pub/sub for instance B.
     */
    private CacheInvalidationPubSub pubsubB;

    @BeforeEach
    void setUp() {
        final String host = VALKEY.getHost();
        final int port = VALKEY.getMappedPort(6379);
        this.connA = new ValkeyConnection(host, port, Duration.ofSeconds(5));
        this.connB = new ValkeyConnection(host, port, Duration.ofSeconds(5));
        this.pubsubA = new CacheInvalidationPubSub(this.connA);
        this.pubsubB = new CacheInvalidationPubSub(this.connB);
    }

    @AfterEach
    void tearDown() {
        if (this.pubsubA != null) {
            this.pubsubA.close();
        }
        if (this.pubsubB != null) {
            this.pubsubB.close();
        }
        if (this.connA != null) {
            this.connA.close();
        }
        if (this.connB != null) {
            this.connB.close();
        }
    }

    @Test
    void invalidatesRemoteCacheForSpecificKey() {
        final RecordingCleanable cache = new RecordingCleanable();
        this.pubsubB.register("auth", cache);
        this.pubsubA.publish("auth", "user:alice");
        Awaitility.await()
            .atMost(5, TimeUnit.SECONDS)
            .untilAsserted(
                () -> MatcherAssert.assertThat(
                    "Instance B should have received invalidation for 'user:alice'",
                    cache.invalidated(),
                    Matchers.contains("user:alice")
                )
            );
    }

    @Test
    void selfMessagesAreIgnored() throws Exception {
        final RecordingCleanable cache = new RecordingCleanable();
        this.pubsubA.register("auth", cache);
        this.pubsubA.publish("auth", "user:bob");
        Thread.sleep(1000);
        MatcherAssert.assertThat(
            "Self-published messages should not trigger local invalidation",
            cache.invalidated(),
            Matchers.empty()
        );
    }

    @Test
    void invalidateAllBroadcastsToRemoteInstances() {
        final RecordingCleanable cache = new RecordingCleanable();
        this.pubsubB.register("policy", cache);
        this.pubsubA.publishAll("policy");
        Awaitility.await()
            .atMost(5, TimeUnit.SECONDS)
            .untilAsserted(
                () -> MatcherAssert.assertThat(
                    "Instance B should have received invalidateAll",
                    cache.invalidatedAll(),
                    Matchers.is(1)
                )
            );
    }

    @Test
    void unknownCacheTypeIsIgnored() throws Exception {
        final RecordingCleanable cache = new RecordingCleanable();
        this.pubsubB.register("auth", cache);
        this.pubsubA.publish("unknown-type", "some-key");
        Thread.sleep(1000);
        MatcherAssert.assertThat(
            "Unknown cache type should not trigger any invalidation",
            cache.invalidated(),
            Matchers.empty()
        );
        MatcherAssert.assertThat(
            "Unknown cache type should not trigger invalidateAll",
            cache.invalidatedAll(),
            Matchers.is(0)
        );
    }

    @Test
    void multipleKeysAreDeliveredInOrder() {
        final RecordingCleanable cache = new RecordingCleanable();
        this.pubsubB.register("filters", cache);
        this.pubsubA.publish("filters", "repo-one");
        this.pubsubA.publish("filters", "repo-two");
        this.pubsubA.publish("filters", "repo-three");
        Awaitility.await()
            .atMost(5, TimeUnit.SECONDS)
            .untilAsserted(
                () -> MatcherAssert.assertThat(
                    "All three keys should be delivered",
                    cache.invalidated(),
                    Matchers.contains("repo-one", "repo-two", "repo-three")
                )
            );
    }

    @Test
    void multipleCacheTypesAreRoutedCorrectly() {
        final RecordingCleanable auth = new RecordingCleanable();
        final RecordingCleanable filters = new RecordingCleanable();
        this.pubsubB.register("auth", auth);
        this.pubsubB.register("filters", filters);
        this.pubsubA.publish("auth", "user:charlie");
        this.pubsubA.publish("filters", "repo-x");
        Awaitility.await()
            .atMost(5, TimeUnit.SECONDS)
            .untilAsserted(
                () -> {
                    MatcherAssert.assertThat(
                        "Auth cache should only receive auth key",
                        auth.invalidated(),
                        Matchers.contains("user:charlie")
                    );
                    MatcherAssert.assertThat(
                        "Filters cache should only receive filters key",
                        filters.invalidated(),
                        Matchers.contains("repo-x")
                    );
                }
            );
    }

    @Test
    void closeStopsReceivingMessages() throws Exception {
        final RecordingCleanable cache = new RecordingCleanable();
        this.pubsubB.register("auth", cache);
        this.pubsubB.close();
        this.pubsubA.publish("auth", "user:after-close");
        Thread.sleep(1000);
        MatcherAssert.assertThat(
            "Closed instance should not receive messages",
            cache.invalidated(),
            Matchers.empty()
        );
        this.pubsubB = null;
    }

    @Test
    void publishingCleanableDelegatesAndPublishes() {
        final RecordingCleanable inner = new RecordingCleanable();
        final RecordingCleanable remote = new RecordingCleanable();
        this.pubsubB.register("auth", remote);
        final PublishingCleanable wrapper =
            new PublishingCleanable(inner, this.pubsubA, "auth");
        wrapper.invalidate("user:delta");
        MatcherAssert.assertThat(
            "Inner cache should be invalidated directly",
            inner.invalidated(),
            Matchers.contains("user:delta")
        );
        Awaitility.await()
            .atMost(5, TimeUnit.SECONDS)
            .untilAsserted(
                () -> MatcherAssert.assertThat(
                    "Remote cache should receive invalidation via pub/sub",
                    remote.invalidated(),
                    Matchers.contains("user:delta")
                )
            );
    }

    @Test
    void publishingCleanableInvalidateAllDelegatesAndPublishes() {
        final RecordingCleanable inner = new RecordingCleanable();
        final RecordingCleanable remote = new RecordingCleanable();
        this.pubsubB.register("policy", remote);
        final PublishingCleanable wrapper =
            new PublishingCleanable(inner, this.pubsubA, "policy");
        wrapper.invalidateAll();
        MatcherAssert.assertThat(
            "Inner cache should receive invalidateAll",
            inner.invalidatedAll(),
            Matchers.is(1)
        );
        Awaitility.await()
            .atMost(5, TimeUnit.SECONDS)
            .untilAsserted(
                () -> MatcherAssert.assertThat(
                    "Remote cache should receive invalidateAll via pub/sub",
                    remote.invalidatedAll(),
                    Matchers.is(1)
                )
            );
    }

    /**
     * Recording implementation of {@link Cleanable} for test verification.
     */
    private static final class RecordingCleanable implements Cleanable<String> {
        /**
         * Keys that were invalidated.
         */
        private final List<String> keys;

        /**
         * Count of invalidateAll calls.
         */
        private int allCount;

        RecordingCleanable() {
            this.keys = Collections.synchronizedList(new ArrayList<>(8));
        }

        @Override
        public void invalidate(final String key) {
            this.keys.add(key);
        }

        @Override
        public void invalidateAll() {
            this.allCount += 1;
        }

        List<String> invalidated() {
            return this.keys;
        }

        int invalidatedAll() {
            return this.allCount;
        }
    }
}
