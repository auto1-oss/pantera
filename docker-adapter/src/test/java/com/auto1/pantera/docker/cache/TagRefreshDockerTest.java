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
package com.auto1.pantera.docker.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.composite.MultiReadDocker;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link TagRefreshDocker} reads its cache as {@link MultiReadDocker} does
 * when the cache fails: a failure is never read as "absent".
 *
 * @since 2.2.15
 */
final class TagRefreshDockerTest {

    @Test
    @Timeout(60)
    void cacheThatFailsIsNotReadAsAbsent() {
        final AstoDocker remote = new AstoDocker("registry", new InMemoryStorage());
        final TagRefreshDocker docker = new TagRefreshDocker(
            new AstoDocker("registry", new Broken(new InMemoryStorage())),
            new MultiReadDocker(remote),
            List.of(),
            new TagChecks(() -> Duration.ofHours(1)),
            true
        );
        final ExecutionException err = Assertions.assertThrows(
            ExecutionException.class,
            () -> docker.repo("my-alpine").manifests()
                .get(ManifestReference.from("1")).get(30, TimeUnit.SECONDS)
        );
        Throwable root = err;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        MatcherAssert.assertThat(
            "the failure of the cache is the answer when no remote has the tag",
            root, new IsInstanceOf(IllegalStateException.class)
        );
    }

    /**
     * Storage whose reads fail.
     *
     * @since 2.2.15
     */
    private static final class Broken extends Storage.Wrap {

        /**
         * Ctor.
         *
         * @param delegate Storage
         */
        Broken(final Storage delegate) {
            super(delegate);
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            return CompletableFuture.failedFuture(new IllegalStateException("storage down"));
        }

        @Override
        public CompletableFuture<Content> value(final Key key) {
            return CompletableFuture.failedFuture(new IllegalStateException("storage down"));
        }
    }
}
