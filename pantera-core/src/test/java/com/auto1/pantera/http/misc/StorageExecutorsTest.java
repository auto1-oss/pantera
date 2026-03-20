/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.misc;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;

/**
 * Tests for {@link StorageExecutors}.
 *
 * @since 1.20.13
 */
final class StorageExecutorsTest {

    @Test
    void readPoolUsesNamedThreads() throws ExecutionException, InterruptedException {
        final String name = StorageExecutors.READ.submit(
            () -> Thread.currentThread().getName()
        ).get();
        assertThat("Read pool thread should have correct name",
            name, startsWith("pantera-io-read-"));
    }

    @Test
    void writePoolUsesNamedThreads() throws ExecutionException, InterruptedException {
        final String name = StorageExecutors.WRITE.submit(
            () -> Thread.currentThread().getName()
        ).get();
        assertThat("Write pool thread should have correct name",
            name, startsWith("pantera-io-write-"));
    }

    @Test
    void listPoolUsesNamedThreads() throws ExecutionException, InterruptedException {
        final String name = StorageExecutors.LIST.submit(
            () -> Thread.currentThread().getName()
        ).get();
        assertThat("List pool thread should have correct name",
            name, startsWith("pantera-io-list-"));
    }

    @Test
    void poolsAreDistinct() {
        assertThat("READ and WRITE should be different pools",
            StorageExecutors.READ, is(not(sameInstance(StorageExecutors.WRITE))));
        assertThat("READ and LIST should be different pools",
            StorageExecutors.READ, is(not(sameInstance(StorageExecutors.LIST))));
        assertThat("WRITE and LIST should be different pools",
            StorageExecutors.WRITE, is(not(sameInstance(StorageExecutors.LIST))));
    }

    @Test
    void threadsAreDaemons() throws ExecutionException, InterruptedException {
        final Boolean isDaemon = StorageExecutors.READ.submit(
            () -> Thread.currentThread().isDaemon()
        ).get();
        assertThat("Pool threads should be daemon threads", isDaemon, is(true));
    }
}
