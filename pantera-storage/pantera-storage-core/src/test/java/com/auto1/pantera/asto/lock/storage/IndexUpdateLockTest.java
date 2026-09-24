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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Test for {@link IndexUpdateLock}.
 *
 * @since 2.2.9
 */
@Timeout(30)
final class IndexUpdateLockTest {

    /**
     * Locked key.
     */
    private static final Key KEY = new Key.From("noarch", "repodata.json");

    @Test
    void secondWriterWaitsForTheFirstInsteadOfFailing() {
        final Storage storage = new InMemoryStorage();
        final CompletableFuture<Void> parked = new CompletableFuture<>();
        final CompletableFuture<Void> entered = new CompletableFuture<>();
        final CompletableFuture<String> first = new IndexUpdateLock(storage, KEY).run(
            asto -> {
                entered.complete(null);
                return parked.thenApply(nothing -> "first");
            }
        );
        entered.join();
        final AtomicInteger inside = new AtomicInteger();
        final CompletableFuture<String> second = new IndexUpdateLock(storage, KEY).run(
            asto -> {
                inside.incrementAndGet();
                return CompletableFuture.completedFuture("second");
            }
        );
        Assertions.assertEquals(
            0, inside.get(), "the second writer must not run while the first holds the lock"
        );
        parked.complete(null);
        Assertions.assertEquals("first", first.join(), "first writer result");
        Assertions.assertEquals(
            "second", second.join(), "the second writer runs once the lock is released"
        );
    }

    @Test
    void releasesTheLockAfterAFailedOperationAndDoesNotRetryIt() {
        final Storage storage = new InMemoryStorage();
        final AtomicInteger calls = new AtomicInteger();
        final CompletableFuture<Object> failed = new IndexUpdateLock(storage, KEY).run(
            asto -> {
                calls.incrementAndGet();
                return CompletableFuture.failedFuture(new IllegalStateException("boom"));
            }
        );
        final CompletionException err = Assertions.assertThrows(
            CompletionException.class, failed::join, "the operation failure is returned"
        );
        MatcherAssert.assertThat(
            "the cause is the operation's own failure",
            err.getCause(), new IsInstanceOf(IllegalStateException.class)
        );
        MatcherAssert.assertThat(
            "a failed operation is not retried", calls.get(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the lock is free again",
            new IndexUpdateLock(storage, KEY)
                .run(asto -> CompletableFuture.completedFuture("next")).join(),
            new IsEqual<>("next")
        );
    }

    @Test
    void givesUpWhenTheLockStaysHeld() {
        final Storage storage = new InMemoryStorage();
        final CompletableFuture<Void> parked = new CompletableFuture<>();
        final CompletableFuture<Void> entered = new CompletableFuture<>();
        final CompletableFuture<Void> holder = new IndexUpdateLock(storage, KEY).run(
            asto -> {
                entered.complete(null);
                return parked;
            }
        );
        entered.join();
        final AtomicInteger calls = new AtomicInteger();
        final CompletableFuture<Object> waiter = new IndexUpdateLock(
            storage, KEY, Duration.ofMinutes(1), 3, Duration.ofMillis(5)
        ).run(
            asto -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
        );
        Assertions.assertThrows(
            CompletionException.class, waiter::join, "acquiring fails after the attempts"
        );
        Assertions.assertEquals(0, calls.get(), "the operation never ran");
        parked.complete(null);
        holder.join();
    }
}
