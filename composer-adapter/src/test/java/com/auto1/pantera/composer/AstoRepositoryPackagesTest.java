/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.composer;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AstoRepository#packages()} and {@link AstoRepository#packages(Name)}.
 *
 * @since 0.3
 */
class AstoRepositoryPackagesTest {

    /**
     * Storage used in tests.
     */
    private Storage storage;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
    }

    @Test
    void shouldLoadEmptyPackages() {
        final Name name = new Name("foo/bar");
        MatcherAssert.assertThat(
            new AstoRepository(this.storage).packages(name)
                .toCompletableFuture().join()
                .isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void shouldLoadNonEmptyPackages() throws Exception {
        final Name name = new Name("foo/bar2");
        final byte[] bytes = "some data".getBytes();
        new BlockingStorage(this.storage).save(name.key(), bytes);
        new AstoRepository(this.storage).packages(name).toCompletableFuture().join().get()
            .save(this.storage, name.key())
            .toCompletableFuture().join();
        MatcherAssert.assertThat(
            new BlockingStorage(this.storage).value(name.key()),
            new IsEqual<>(bytes)
        );
    }

    @Test
    void shouldLoadEmptyAllPackages() {
        // With Satis layout, packages() always returns an index (never empty)
        MatcherAssert.assertThat(
            "Satis index should always be present",
            new AstoRepository(this.storage).packages().toCompletableFuture().join().isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void shouldLoadNonEmptyAllPackages() throws Exception {
        // With Satis layout, packages() returns the Satis index JSON
        final Packages allPkgs = new AstoRepository(this.storage).packages()
            .toCompletableFuture().join().get();
        allPkgs.save(this.storage, new AllPackages())
            .toCompletableFuture().join();
        // Verify saved packages.json contains Satis index structure
        final byte[] saved = new BlockingStorage(this.storage).value(new AllPackages());
        final String json = new String(saved);
        MatcherAssert.assertThat(
            "Should contain metadata-url",
            json.contains("metadata-url"),
            new IsEqual<>(true)
        );
    }
}
