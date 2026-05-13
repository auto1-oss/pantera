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
package com.auto1.pantera.helm;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.helm.metadata.IndexYaml;
import com.auto1.pantera.helm.metadata.IndexYamlMapping;
import com.auto1.pantera.helm.test.ContentOfIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.cactoos.set.SetOf;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Test for {@link Helm.Asto#reindex(Key)}.
 * @since 0.3
 */
final class HelmAstoReindexTest {
    /**
     * Storage.
     */
    private Storage storage;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reindexFromRootDirectory(final boolean withindex) throws IOException {
        Stream.of(
            "tomcat-0.4.1.tgz", "ark-1.0.1.tgz", "ark-1.2.0.tgz"
        ).forEach(tgz -> new TestResource(tgz).saveTo(this.storage));
        if (withindex) {
            new TestResource("index/index-one-ark.yaml").saveTo(this.storage, IndexYaml.INDEX_YAML);
        }
        new Helm.Asto(this.storage).reindex(Key.ROOT).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "Index file is absent",
            this.storage.exists(IndexYaml.INDEX_YAML).join(),
            new IsEqual<>(true)
        );
        final IndexYamlMapping index = new ContentOfIndex(this.storage).index();
        MatcherAssert.assertThat(
            "Written charts are wrong",
            index.entries().keySet(),
            Matchers.containsInAnyOrder("tomcat", "ark")
        );
        HelmAstoReindexTest.assertTmpDirWasRemoved();
    }

    @Test
    void reindexWithSomePrefix() throws IOException {
        final Key prfx = new Key.From("prefix");
        Stream.of("ark-1.0.1.tgz", "ark-1.2.0.tgz")
            .forEach(tgz -> new TestResource(tgz).saveTo(this.storage, new Key.From(prfx, tgz)));
        new Helm.Asto(this.storage).reindex(prfx).toCompletableFuture().join();
        final Key keyidx = new Key.From(prfx, IndexYaml.INDEX_YAML);
        MatcherAssert.assertThat(
            "Index file is absent",
            this.storage.exists(keyidx).join(),
            new IsEqual<>(true)
        );
        final IndexYamlMapping index = new ContentOfIndex(this.storage).index(keyidx);
        MatcherAssert.assertThat(
            "Written charts are wrong",
            index.entries().keySet(),
            new IsEqual<>(new SetOf<String>("ark"))
        );
        HelmAstoReindexTest.assertTmpDirWasRemoved();
    }

    private static void assertTmpDirWasRemoved() throws IOException {
        final Path systemtemp = Paths.get(System.getProperty("java.io.tmpdir"));
        MatcherAssert.assertThat(
            "Temp dir for indexes was not removed",
            Files.list(systemtemp)
                .noneMatch(path -> path.getFileName().toString().startsWith("index-")),
            new IsEqual<>(true)
        );
    }
}
