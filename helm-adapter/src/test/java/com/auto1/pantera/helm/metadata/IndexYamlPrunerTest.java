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
package com.auto1.pantera.helm.metadata;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link IndexYamlPruner}.
 *
 * @since 2.2.9
 */
final class IndexYamlPrunerTest {

    /**
     * Storage.
     */
    private Storage asto;

    @BeforeEach
    void init() {
        this.asto = new InMemoryStorage();
        this.asto.save(
            IndexYaml.INDEX_YAML,
            new Content.From(
                String.join(
                    "\n",
                    "apiVersion: v1",
                    "entries:",
                    "  qa:",
                    "  - name: qa",
                    "    version: 1.0.0",
                    "    created: '2026-01-01T00:00:00Z'",
                    "    urls: [qa/qa-1.0.0.tgz]",
                    "  - name: qa",
                    "    version: 2.0.0",
                    "    created: '2026-01-02T00:00:00Z'",
                    "    urls: [qa/qa-2.0.0.tgz]",
                    "  other:",
                    "  - name: other",
                    "    version: '1.0'",
                    "    created: '2026-01-03T00:00:00Z'",
                    "    urls: [other/other-1.0.tgz]",
                    "generated: '2026-01-03T00:00:00Z'",
                    ""
                ).getBytes(StandardCharsets.UTF_8)
            )
        ).join();
        for (final String key : new String[] {
            "qa/qa-1.0.0.tgz", "qa/qa-2.0.0.tgz", "other/other-1.0.tgz",
        }) {
            this.asto.save(new Key.From(key), Content.EMPTY).join();
        }
    }

    @Test
    void dropsTheDeletedChartVersion() {
        this.asto.delete(new Key.From("qa/qa-2.0.0.tgz")).join();
        new IndexYamlPruner(this.asto).afterDelete("qa/qa-2.0.0.tgz").join();
        final IndexYamlMapping index = this.index();
        MatcherAssert.assertThat(
            "only the remaining version of the chart is listed",
            IndexYamlPrunerTest.versions(index, "qa"), new IsEqual<>(List.of("1.0.0"))
        );
        MatcherAssert.assertThat(
            "the remaining version keeps its creation time",
            String.valueOf(index.byChart("qa").get(0).get("created")),
            new IsEqual<>("2026-01-01T00:00:00Z")
        );
        MatcherAssert.assertThat(
            "other charts are untouched",
            IndexYamlPrunerTest.versions(index, "other"), new IsEqual<>(List.of("1.0"))
        );
    }

    @Test
    void dropsTheChartWhenItsFolderIsDeleted() {
        this.asto.delete(new Key.From("qa/qa-1.0.0.tgz")).join();
        this.asto.delete(new Key.From("qa/qa-2.0.0.tgz")).join();
        new IndexYamlPruner(this.asto).afterDelete("qa").join();
        MatcherAssert.assertThat(
            this.index().entries().containsKey("qa"), new IsEqual<>(false)
        );
    }

    @Test
    void leavesTheIndexAloneWhenEveryChartIsStored() {
        final String before = this.asto.value(IndexYaml.INDEX_YAML).join().asString();
        new IndexYamlPruner(this.asto).afterDelete("qa/unrelated.txt").join();
        MatcherAssert.assertThat(
            this.asto.value(IndexYaml.INDEX_YAML).join().asString(), new IsEqual<>(before)
        );
    }

    /**
     * Stored index.
     * @return Mapping
     */
    private IndexYamlMapping index() {
        return new IndexYamlMapping(this.asto.value(IndexYaml.INDEX_YAML).join().asString());
    }

    /**
     * Versions of a chart.
     * @param index Index
     * @param chart Chart
     * @return Versions
     */
    private static List<String> versions(final IndexYamlMapping index, final String chart) {
        return index.byChart(chart).stream()
            .map((Map<String, Object> entry) -> String.valueOf(entry.get("version")))
            .collect(Collectors.toList());
    }
}
