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
package com.auto1.pantera.asto.s3;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListPage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Paged listing and batch delete through {@link DiskCacheStorage}.
 *
 * @since 2.2.14
 */
final class DiskCacheStorageBatchTest {

    @Test
    @Timeout(60)
    void batchDeleteEvictsCachedCopiesAndDeletesAtBackend(@TempDir final Path tmp)
        throws Exception {
        final InMemoryStorage backend = new InMemoryStorage();
        final List<Key> keys = List.of(
            new Key.From("repo/a.jar"), new Key.From("repo/sub/b.jar"), new Key.From("repo/c.jar")
        );
        for (final Key key : keys) {
            backend.save(
                key, new Content.From(key.string().getBytes(StandardCharsets.UTF_8))
            ).join();
        }
        try (DiskCacheStorage cache = new DiskCacheStorage(
            backend, tmp, 1024 * 1024, DiskCacheStorage.Policy.LRU, 0, 90, 80, false
        )) {
            for (final Key key : keys) {
                cache.value(key).get(30, TimeUnit.SECONDS).asBytesFuture()
                    .get(30, TimeUnit.SECONDS);
            }
            MatcherAssert.assertThat(
                "reading the keys cached them on disk",
                DiskCacheStorageBatchTest.cached(tmp),
                new IsEqual<>(List.of("a.jar", "b.jar", "c.jar"))
            );
            cache.deleteBatch(
                List.of(keys.get(0), keys.get(1), new Key.From("repo/never-cached.jar"))
            ).get(30, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                "the cached copies of the deleted keys are evicted, the other stays",
                DiskCacheStorageBatchTest.cached(tmp),
                new IsEqual<>(List.of("c.jar"))
            );
            MatcherAssert.assertThat(
                "the keys are deleted at the backend",
                backend.list(Key.ROOT).join().stream()
                    .map(Key::string).collect(Collectors.toList()),
                new IsEqual<>(List.of("repo/c.jar"))
            );
            MatcherAssert.assertThat(
                "a paged listing is the backend's",
                cache.listPage(new Key.From("repo"), Optional.empty(), 10)
                    .get(30, TimeUnit.SECONDS),
                new IsEqual<>(
                    new ListPage(List.of(new Key.From("repo/c.jar")), Optional.empty())
                )
            );
        }
    }

    /**
     * Names of the artifact files in the cache directory.
     * @param dir Cache directory
     * @return Sorted file names, sidecars left out
     * @throws IOException On error
     */
    private static List<String> cached(final Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".jar"))
                .sorted()
                .collect(Collectors.toList());
        }
    }
}
