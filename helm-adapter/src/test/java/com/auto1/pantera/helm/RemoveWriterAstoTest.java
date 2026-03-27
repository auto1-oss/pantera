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

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.helm.metadata.IndexYaml;
import com.auto1.pantera.helm.metadata.IndexYamlMapping;
import com.auto1.pantera.helm.test.ContentOfIndex;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * Test for {@link RemoveWriter.Asto}.
 */
final class RemoveWriterAstoTest {

    @TempDir
    Path dir;

    /**
     * Key to source index file.
     */
    private Key source;

    /**
     * Path for index file where it will rewritten.
     */
    private Path out;

    private Storage storage;

    @BeforeEach
    void setUp() throws IOException {
        final String prfx = "index-";
        this.source = IndexYaml.INDEX_YAML;
        this.out = Files.createTempFile(this.dir, prfx, "-out.yaml");
        this.storage = new FileStorage(this.dir);
    }

    @ParameterizedTest
    @ValueSource(strings = {"index.yaml", "index/index-four-spaces.yaml"})
    void deletesOneOfManyVersionOfChart(final String idx) {
        final String chart = "ark-1.0.1.tgz";
        new TestResource(idx).saveTo(this.storage, this.source);
        new TestResource(chart).saveTo(this.storage);
        this.delete(chart);
        final IndexYamlMapping index = new ContentOfIndex(this.storage).index(this.pathToIndex());
        Assertions.assertFalse(
            index.byChartAndVersion("ark", "1.0.1").isPresent(),
            "Removed version exists"
        );
        Assertions.assertTrue(
            index.byChartAndVersion("ark", "1.2.0").isPresent(),
            "Extra version of chart was deleted"
        );
        Assertions.assertTrue(
            index.byChartAndVersion("tomcat", "0.4.1").isPresent(),
            "Extra chart was deleted"
        );
    }

    @Test
    void deletesAllVersionOfChart() {
        final String arkone = "ark-1.0.1.tgz";
        final String arktwo = "ark-1.2.0.tgz";
        new TestResource("index.yaml").saveTo(this.storage, this.source);
        new TestResource(arkone).saveTo(this.storage);
        new TestResource(arktwo).saveTo(this.storage);
        this.delete(arkone, arktwo);
        final IndexYamlMapping index = new ContentOfIndex(this.storage).index(this.pathToIndex());
        MatcherAssert.assertThat(
            "Removed versions exist",
            index.byChart("ark").isEmpty(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Extra chart was deleted",
            index.byChartAndVersion("tomcat", "0.4.1").isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void deleteLastChartFromIndex() {
        final String chart = "ark-1.0.1.tgz";
        new TestResource("index/index-one-ark.yaml").saveTo(this.storage, this.source);
        new TestResource(chart).saveTo(this.storage);
        this.delete(chart);
        Assertions.assertTrue(
            new ContentOfIndex(this.storage).index(this.pathToIndex())
                .entries().isEmpty()
        );
    }

    @Test
    void failsToDeleteAbsentInIndexChart() {
        final String chart = "tomcat-0.4.1.tgz";
        new TestResource("index/index-one-ark.yaml").saveTo(this.storage, this.source);
        new TestResource(chart).saveTo(this.storage);
        final Throwable thr = Assertions.assertThrows(
            CompletionException.class,
            () -> this.delete(chart)
        );
        MatcherAssert.assertThat(
            thr.getCause(),
            new IsInstanceOf(PanteraException.class)
        );
    }

    private void delete(final String... charts) {
        final Collection<Key> keys = Arrays.stream(charts)
            .map(Key.From::new)
            .collect(Collectors.toList());
        final Map<String, Set<String>> todelete = new HashMap<>();
        keys.forEach(
            key -> {
                final ChartYaml chart = new TgzArchive(
                    this.storage.value(key).join().asBytes()
                ).chartYaml();
                todelete.putIfAbsent(chart.name(), new HashSet<>());
                todelete.get(chart.name()).add(chart.version());
            }
        );
        new RemoveWriter.Asto(this.storage)
            .delete(this.source, this.out, todelete)
            .toCompletableFuture().join();
    }

    private Key pathToIndex() {
        return new Key.From(this.out.getFileName().toString());
    }
}
