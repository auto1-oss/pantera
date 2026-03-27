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
package com.auto1.pantera.settings;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.scheduling.QuartzService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link YamlSettings} prefix reading.
 */
class YamlSettingsPrefixesTest {

    @Test
    void readsPrefixesFromYaml(@TempDir final Path temp) throws Exception {
        final YamlMapping yaml = Yaml.createYamlMappingBuilder()
            .add("meta", Yaml.createYamlMappingBuilder()
                .add("storage", Yaml.createYamlMappingBuilder()
                    .add("type", "fs")
                    .add("path", temp.toString())
                    .build()
                )
                .add("global_prefixes", Yaml.createYamlSequenceBuilder()
                    .add("p1")
                    .add("p2")
                    .add("migration")
                    .build()
                )
                .build()
            )
            .build();

        final QuartzService quartz = new QuartzService();
        try {
            final Settings settings = new YamlSettings(yaml, temp, quartz);
            final PrefixesConfig prefixes = settings.prefixes();

            final List<String> list = prefixes.prefixes();
            assertEquals(3, list.size());
            assertTrue(list.contains("p1"));
            assertTrue(list.contains("p2"));
            assertTrue(list.contains("migration"));
        } finally {
            quartz.stop();
        }
    }

    @Test
    void handlesEmptyPrefixes(@TempDir final Path temp) throws Exception {
        final YamlMapping yaml = Yaml.createYamlMappingBuilder()
            .add("meta", Yaml.createYamlMappingBuilder()
                .add("storage", Yaml.createYamlMappingBuilder()
                    .add("type", "fs")
                    .add("path", temp.toString())
                    .build()
                )
                .add("global_prefixes", Yaml.createYamlSequenceBuilder().build())
                .build()
            )
            .build();

        final QuartzService quartz = new QuartzService();
        try {
            final Settings settings = new YamlSettings(yaml, temp, quartz);
            final PrefixesConfig prefixes = settings.prefixes();

            assertTrue(prefixes.prefixes().isEmpty());
        } finally {
            quartz.stop();
        }
    }

    @Test
    void handlesNoPrefixesSection(@TempDir final Path temp) throws Exception {
        final YamlMapping yaml = Yaml.createYamlMappingBuilder()
            .add("meta", Yaml.createYamlMappingBuilder()
                .add("storage", Yaml.createYamlMappingBuilder()
                    .add("type", "fs")
                    .add("path", temp.toString())
                    .build()
                )
                .build()
            )
            .build();

        final QuartzService quartz = new QuartzService();
        try {
            final Settings settings = new YamlSettings(yaml, temp, quartz);
            final PrefixesConfig prefixes = settings.prefixes();

            assertTrue(prefixes.prefixes().isEmpty());
        } finally {
            quartz.stop();
        }
    }

    @Test
    void filtersBlankPrefixes(@TempDir final Path temp) throws Exception {
        final YamlMapping yaml = Yaml.createYamlMappingBuilder()
            .add("meta", Yaml.createYamlMappingBuilder()
                .add("storage", Yaml.createYamlMappingBuilder()
                    .add("type", "fs")
                    .add("path", temp.toString())
                    .build()
                )
                .add("global_prefixes", Yaml.createYamlSequenceBuilder()
                    .add("p1")
                    .add("")
                    .add("p2")
                    .add("   ")
                    .build()
                )
                .build()
            )
            .build();

        final QuartzService quartz = new QuartzService();
        try {
            final Settings settings = new YamlSettings(yaml, temp, quartz);
            final PrefixesConfig prefixes = settings.prefixes();

            final List<String> list = prefixes.prefixes();
            assertEquals(2, list.size());
            assertTrue(list.contains("p1"));
            assertTrue(list.contains("p2"));
        } finally {
            quartz.stop();
        }
    }
}
