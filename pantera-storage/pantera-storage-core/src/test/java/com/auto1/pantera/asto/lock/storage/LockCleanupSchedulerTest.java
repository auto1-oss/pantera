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
package com.auto1.pantera.asto.lock.storage;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Test cases for {@link LockCleanupScheduler}.
 *
 * @since 1.20.13
 */
@Timeout(5)
final class LockCleanupSchedulerTest {

    @Test
    void removesExpiredProposals() {
        final InMemoryStorage storage = new InMemoryStorage();
        final Key target = new Key.From("my/target");
        final String uuid = UUID.randomUUID().toString();
        final Key proposal = new Key.From(new Proposals.RootKey(target), uuid);
        final Instant expired = Instant.now().minus(Duration.ofHours(1));
        new BlockingStorage(storage).save(
            proposal,
            expired.toString().getBytes(StandardCharsets.US_ASCII)
        );
        final LockCleanupScheduler scheduler = new LockCleanupScheduler(storage);
        try {
            scheduler.runOnce().join();
            MatcherAssert.assertThat(
                "Expired proposal should be deleted",
                storage.exists(proposal).toCompletableFuture().join(),
                new IsEqual<>(false)
            );
        } finally {
            scheduler.close();
        }
    }

    @Test
    void keepsNonExpiredProposals() {
        final InMemoryStorage storage = new InMemoryStorage();
        final Key target = new Key.From("my/target");
        final String uuid = UUID.randomUUID().toString();
        final Key proposal = new Key.From(new Proposals.RootKey(target), uuid);
        final Instant future = Instant.now().plus(Duration.ofHours(1));
        new BlockingStorage(storage).save(
            proposal,
            future.toString().getBytes(StandardCharsets.US_ASCII)
        );
        final LockCleanupScheduler scheduler = new LockCleanupScheduler(storage);
        try {
            scheduler.runOnce().join();
            MatcherAssert.assertThat(
                "Non-expired proposal should survive cleanup",
                storage.exists(proposal).toCompletableFuture().join(),
                new IsEqual<>(true)
            );
        } finally {
            scheduler.close();
        }
    }

    @Test
    void keepsProposalsWithNoExpiration() {
        final InMemoryStorage storage = new InMemoryStorage();
        final Key target = new Key.From("my/target");
        final String uuid = UUID.randomUUID().toString();
        final Key proposal = new Key.From(new Proposals.RootKey(target), uuid);
        storage.save(proposal, Content.EMPTY).toCompletableFuture().join();
        final LockCleanupScheduler scheduler = new LockCleanupScheduler(storage);
        try {
            scheduler.runOnce().join();
            MatcherAssert.assertThat(
                "Proposal with no expiration should survive cleanup",
                storage.exists(proposal).toCompletableFuture().join(),
                new IsEqual<>(true)
            );
        } finally {
            scheduler.close();
        }
    }

    @Test
    void removesExpiredButKeepsActive() {
        final InMemoryStorage storage = new InMemoryStorage();
        final Key target = new Key.From("shared/resource");
        final String expiredUuid = UUID.randomUUID().toString();
        final String activeUuid = UUID.randomUUID().toString();
        final Key expiredProposal = new Key.From(
            new Proposals.RootKey(target), expiredUuid
        );
        final Key activeProposal = new Key.From(
            new Proposals.RootKey(target), activeUuid
        );
        new BlockingStorage(storage).save(
            expiredProposal,
            Instant.now().minus(Duration.ofMinutes(30)).toString()
                .getBytes(StandardCharsets.US_ASCII)
        );
        new BlockingStorage(storage).save(
            activeProposal,
            Instant.now().plus(Duration.ofMinutes(30)).toString()
                .getBytes(StandardCharsets.US_ASCII)
        );
        final LockCleanupScheduler scheduler = new LockCleanupScheduler(storage);
        try {
            scheduler.runOnce().join();
            MatcherAssert.assertThat(
                "Expired proposal should be deleted",
                storage.exists(expiredProposal).toCompletableFuture().join(),
                new IsEqual<>(false)
            );
            MatcherAssert.assertThat(
                "Active proposal should survive",
                storage.exists(activeProposal).toCompletableFuture().join(),
                new IsEqual<>(true)
            );
        } finally {
            scheduler.close();
        }
    }

    @Test
    void handlesEmptyStorage() {
        final InMemoryStorage storage = new InMemoryStorage();
        final LockCleanupScheduler scheduler = new LockCleanupScheduler(storage);
        try {
            scheduler.runOnce().join();
        } finally {
            scheduler.close();
        }
    }

    @Test
    void cleanExpiredInProposals() {
        final InMemoryStorage storage = new InMemoryStorage();
        final Key target = new Key.From("test/key");
        final String expiredUuid = UUID.randomUUID().toString();
        final String activeUuid = UUID.randomUUID().toString();
        final Key expiredKey = new Key.From(new Proposals.RootKey(target), expiredUuid);
        final Key activeKey = new Key.From(new Proposals.RootKey(target), activeUuid);
        new BlockingStorage(storage).save(
            expiredKey,
            Instant.now().minus(Duration.ofHours(2)).toString()
                .getBytes(StandardCharsets.US_ASCII)
        );
        new BlockingStorage(storage).save(
            activeKey,
            Instant.now().plus(Duration.ofHours(2)).toString()
                .getBytes(StandardCharsets.US_ASCII)
        );
        new Proposals(storage, target).cleanExpired().toCompletableFuture().join();
        MatcherAssert.assertThat(
            "Expired proposal removed by Proposals.cleanExpired()",
            storage.exists(expiredKey).toCompletableFuture().join(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "Active proposal kept by Proposals.cleanExpired()",
            storage.exists(activeKey).toCompletableFuture().join(),
            new IsEqual<>(true)
        );
    }
}
