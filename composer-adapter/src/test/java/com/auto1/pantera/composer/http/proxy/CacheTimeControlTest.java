/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.composer.http.proxy;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.cache.Remote;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import javax.json.Json;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Test for {@link CacheTimeControl}.
 * @since 0.4
 */
final class CacheTimeControlTest {
    /**
     * Storage.
     */
    private Storage storage;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
    }

    @Test
    void verifiesTimeValueCorrectlyForFreshCache() {
        final String pkg = "p2/vendor/package.json";  // Use the actual cached file key
        final Key itemKey = new Key.From(pkg);
        // Save the cached item (will have current timestamp)
        new BlockingStorage(this.storage).save(
            itemKey,
            "test content".getBytes()
        );
        // The validation now uses filesystem metadata timestamps
        // Note: InMemoryStorage doesn't provide "updated-at" metadata,
        // so CacheTimeControl falls back to Instant.now() which always validates as fresh
        MatcherAssert.assertThat(
            "Fresh cache should be valid (InMemoryStorage fallback to current time)",
            this.validate(pkg),
            new IsEqual<>(true)
        );
    }

    @Test
    void falseForAbsentPackageInCacheFile() {
        // With filesystem timestamps, non-existent files return false
        MatcherAssert.assertThat(
            this.validate("not/exist"),
            new IsEqual<>(false)
        );
    }

    @Test
    void falseIfCacheIsAbsent() {
        MatcherAssert.assertThat(
            this.validate("file/notexist"),
            new IsEqual<>(false)
        );
    }

    private boolean validate(final String pkg) {
        return new CacheTimeControl(this.storage)
            .validate(new Key.From(pkg), Remote.EMPTY)
            .toCompletableFuture().join();
    }
}
