/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.settings;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMappingBuilder;
import com.amihaiemil.eoyaml.YamlSequenceBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ConfigWatchService}.
 * 
 * These tests verify the YAML parsing and configuration update logic
 * by using reflection to access the reload method directly, avoiding
 * unreliable file system watcher timing issues.
 */
class ConfigWatchServiceTest {

    @Test
    void reloadsPrefixesFromYaml(@TempDir final Path temp) throws Exception {
        final Path configFile = temp.resolve("pantera.yml");
        
        // Create config with prefixes FIRST
        writeConfig(configFile, Arrays.asList("p1", "p2", "p3"));
        
        // Ensure file is written
        assertTrue(configFile.toFile().exists());
        
        final PrefixesConfig prefixes = new PrefixesConfig(Arrays.asList("old"));
        try (ConfigWatchService watch = new ConfigWatchService(configFile, prefixes)) {
            // Use reflection to call reload() directly
            final java.lang.reflect.Method reloadMethod = 
                ConfigWatchService.class.getDeclaredMethod("reload");
            reloadMethod.setAccessible(true);
            reloadMethod.invoke(watch);
            
            // Verify reload happened
            final List<String> updated = prefixes.prefixes();
            assertEquals(3, updated.size());
            assertTrue(updated.contains("p1"));
            assertTrue(updated.contains("p2"));
            assertTrue(updated.contains("p3"));
            assertEquals(1L, prefixes.version());
        }
    }

    @Test
    void reloadsMultipleTimes(@TempDir final Path temp) throws Exception {
        final Path configFile = temp.resolve("pantera.yml");
        
        // Create initial config
        writeConfig(configFile, Arrays.asList("p1", "p2"));
        
        final PrefixesConfig prefixes = new PrefixesConfig(Arrays.asList());
        try (ConfigWatchService watch = new ConfigWatchService(configFile, prefixes)) {
            final java.lang.reflect.Method reloadMethod = 
                ConfigWatchService.class.getDeclaredMethod("reload");
            reloadMethod.setAccessible(true);
            
            // First reload
            reloadMethod.invoke(watch);
            assertEquals(2, prefixes.prefixes().size());
            assertEquals(1L, prefixes.version());
            
            // Second reload
            writeConfig(configFile, Arrays.asList("p1", "p2", "p3"));
            reloadMethod.invoke(watch);
            assertEquals(3, prefixes.prefixes().size());
            assertEquals(2L, prefixes.version());
            
            // Third reload
            writeConfig(configFile, Arrays.asList("p1", "p2", "p3", "p4"));
            reloadMethod.invoke(watch);
            assertEquals(4, prefixes.prefixes().size());
            assertEquals(3L, prefixes.version());
        }
    }

    @Test
    void handlesEmptyPrefixList(@TempDir final Path temp) throws Exception {
        final Path configFile = temp.resolve("pantera.yml");
        
        // Create config with empty prefixes
        writeConfig(configFile, Arrays.asList());
        
        final PrefixesConfig prefixes = new PrefixesConfig(Arrays.asList("p1"));
        try (ConfigWatchService watch = new ConfigWatchService(configFile, prefixes)) {
            final java.lang.reflect.Method reloadMethod = 
                ConfigWatchService.class.getDeclaredMethod("reload");
            reloadMethod.setAccessible(true);
            reloadMethod.invoke(watch);
            
            assertTrue(prefixes.prefixes().isEmpty());
            assertEquals(1L, prefixes.version());
        }
    }

    @Test
    void handlesConfigWithoutPrefixes(@TempDir final Path temp) throws Exception {
        final Path configFile = temp.resolve("pantera.yml");
        
        // Create config without global_prefixes
        final YamlMappingBuilder meta = Yaml.createYamlMappingBuilder()
            .add("storage", Yaml.createYamlMappingBuilder()
                .add("type", "fs")
                .add("path", "/tmp/artipie")
                .build()
            );
        
        final String yaml = Yaml.createYamlMappingBuilder()
            .add("meta", meta.build())
            .build()
            .toString();
        
        Files.write(configFile, yaml.getBytes(StandardCharsets.UTF_8));
        
        final PrefixesConfig prefixes = new PrefixesConfig(Arrays.asList("p1"));
        try (ConfigWatchService watch = new ConfigWatchService(configFile, prefixes)) {
            final java.lang.reflect.Method reloadMethod = 
                ConfigWatchService.class.getDeclaredMethod("reload");
            reloadMethod.setAccessible(true);
            reloadMethod.invoke(watch);
            
            // Should clear prefixes
            assertTrue(prefixes.prefixes().isEmpty());
            assertEquals(1L, prefixes.version());
        }
    }

    private void writeConfig(final Path path, final List<String> prefixes) throws IOException {
        YamlSequenceBuilder seqBuilder = Yaml.createYamlSequenceBuilder();
        for (final String prefix : prefixes) {
            seqBuilder = seqBuilder.add(prefix);
        }
        
        YamlMappingBuilder meta = Yaml.createYamlMappingBuilder()
            .add("storage", Yaml.createYamlMappingBuilder()
                .add("type", "fs")
                .add("path", "/tmp/artipie")
                .build()
            );
        
        if (!prefixes.isEmpty()) {
            meta = meta.add("global_prefixes", seqBuilder.build());
        }
        
        final String yaml = Yaml.createYamlMappingBuilder()
            .add("meta", meta.build())
            .build()
            .toString();
        
        Files.write(path, yaml.getBytes(StandardCharsets.UTF_8));
    }
}
