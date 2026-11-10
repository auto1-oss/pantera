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
package com.auto1.pantera.cache;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.blob.StorageInvalidationBus;
import java.util.ArrayList;
import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link PubSubStorageInvalidationBus} (WS1.5, spec {@code
 * WS1-storage-for-scale.md} &sect;3.E): proves the real bus correctly
 * encodes/relays {@code CachedBlobStorage}'s (pantera-storage-core) messages
 * over {@link CacheBroadcast}, fans a delivered message out to every
 * registered listener (multi-repository sharing), and inherits {@link
 * CacheBroadcast}'s self-message filtering. Uses {@link FakeCacheBroadcast}
 * (no Testcontainers Valkey) per CLAUDE.md testing doctrine.
 */
@Timeout(15)
final class PubSubStorageInvalidationBusTest {

    @Test
    void publishOnOneNodeIsDeliveredToAPeerNodeWithTheSameKeyAndToken() {
        final FakeCacheBroadcast.Bus wire = new FakeCacheBroadcast.Bus();
        final StorageInvalidationBus nodeA = new PubSubStorageInvalidationBus(new FakeCacheBroadcast(wire));
        final StorageInvalidationBus nodeB = new PubSubStorageInvalidationBus(new FakeCacheBroadcast(wire));
        final List<Key> receivedKeys = new ArrayList<>();
        final List<String> receivedTokens = new ArrayList<>();
        nodeB.onInvalidate((key, token) -> {
            receivedKeys.add(key);
            receivedTokens.add(token);
        });

        final Key key = new Key.From("group", "artifact.jar");
        nodeA.publish(key, "namespace-digest-12345");

        MatcherAssert.assertThat("the peer must receive exactly one delivery", receivedKeys.size(), new IsEqual<>(1));
        MatcherAssert.assertThat(receivedKeys.get(0), new IsEqual<>(key));
        MatcherAssert.assertThat(
            "the version token must round-trip through the wire encoding unchanged",
            receivedTokens.get(0), new IsEqual<>("namespace-digest-12345")
        );
    }

    @Test
    void publishIsNeverDeliveredBackToThePublishingNode() {
        final FakeCacheBroadcast.Bus wire = new FakeCacheBroadcast.Bus();
        final StorageInvalidationBus nodeA = new PubSubStorageInvalidationBus(new FakeCacheBroadcast(wire));
        final List<Key> selfDeliveries = new ArrayList<>();
        nodeA.onInvalidate((key, token) -> selfDeliveries.add(key));

        nodeA.publish(new Key.From("k.jar"), "token");

        MatcherAssert.assertThat(
            "a node must never receive its own publish (inherited from CacheBroadcast's self-message filter)",
            selfDeliveries.size(), new IsEqual<>(0)
        );
    }

    @Test
    void aMessageIsFannedOutToEveryListenerRegisteredOnTheSameBus() {
        // Simulates several CachedBlobStorage instances (one per repository)
        // sharing ONE process-wide PubSubStorageInvalidationBus.
        final FakeCacheBroadcast.Bus wire = new FakeCacheBroadcast.Bus();
        final StorageInvalidationBus nodeA = new PubSubStorageInvalidationBus(new FakeCacheBroadcast(wire));
        final StorageInvalidationBus nodeB = new PubSubStorageInvalidationBus(new FakeCacheBroadcast(wire));
        final List<Key> repoOneDeliveries = new ArrayList<>();
        final List<Key> repoTwoDeliveries = new ArrayList<>();
        nodeB.onInvalidate((key, token) -> repoOneDeliveries.add(key));
        nodeB.onInvalidate((key, token) -> repoTwoDeliveries.add(key));

        nodeA.publish(new Key.From("k.jar"), "token");

        MatcherAssert.assertThat("every registered listener must receive the message", repoOneDeliveries.size(), new IsEqual<>(1));
        MatcherAssert.assertThat("every registered listener must receive the message", repoTwoDeliveries.size(), new IsEqual<>(1));
    }

    @Test
    void aKeyContainingMultiplePartsRoundTripsThroughTheWireEncoding() {
        final FakeCacheBroadcast.Bus wire = new FakeCacheBroadcast.Bus();
        final StorageInvalidationBus nodeA = new PubSubStorageInvalidationBus(new FakeCacheBroadcast(wire));
        final StorageInvalidationBus nodeB = new PubSubStorageInvalidationBus(new FakeCacheBroadcast(wire));
        final List<Key> received = new ArrayList<>();
        nodeB.onInvalidate((key, token) -> received.add(key));

        final Key key = new Key.From("group-a", "artifact-b", "1.0.0", "artifact-b-1.0.0.jar");
        nodeA.publish(key, "some-token");

        MatcherAssert.assertThat(received.get(0), new IsEqual<>(key));
    }
}
