/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.settings;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlMappingBuilder;
import com.amihaiemil.eoyaml.YamlNode;
import com.amihaiemil.eoyaml.YamlSequenceBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Service for persisting global prefixes configuration to artipie.yaml file.
 * Handles reading, updating, and writing the YAML configuration atomically.
 *
 * @since 1.0
 */
public final class PrefixesPersistence {

    /**
     * Path to artipie.yaml file.
     */
    private final Path configPath;

    /**
     * Constructor.
     *
     * @param configPath Path to artipie.yaml file
     */
    public PrefixesPersistence(final Path configPath) {
        this.configPath = configPath;
    }

    /**
     * Save prefixes to artipie.yaml file.
     * Reads the current file, updates only the global_prefixes section,
     * and writes it back atomically.
     *
     * @param prefixes List of prefixes to save
     * @throws IOException If file operations fail
     */
    public void save(final List<String> prefixes) throws IOException {
        try {
            // Read current YAML file
            final String content = Files.readString(this.configPath, StandardCharsets.UTF_8);
            final YamlMapping currentYaml = Yaml.createYamlInput(content).readYamlMapping();

            // Get meta section
            final YamlMapping meta = currentYaml.yamlMapping("meta");
            if (meta == null) {
                throw new IllegalStateException(
                    "No 'meta' section found in artipie.yaml"
                );
            }

            // Build new global_prefixes sequence
            YamlSequenceBuilder seqBuilder = Yaml.createYamlSequenceBuilder();
            for (final String prefix : prefixes) {
                seqBuilder = seqBuilder.add(prefix);
            }

            // Rebuild meta section with updated global_prefixes
            YamlMappingBuilder metaBuilder = Yaml.createYamlMappingBuilder();
            for (final YamlNode key : meta.keys()) {
                final String keyStr = key.asScalar().value();
                if (!"global_prefixes".equals(keyStr)) {
                    metaBuilder = metaBuilder.add(keyStr, meta.value(keyStr));
                }
            }
            metaBuilder = metaBuilder.add("global_prefixes", seqBuilder.build());

            // Rebuild full YAML with updated meta
            YamlMappingBuilder fullBuilder = Yaml.createYamlMappingBuilder();
            for (final YamlNode key : currentYaml.keys()) {
                final String keyStr = key.asScalar().value();
                if ("meta".equals(keyStr)) {
                    fullBuilder = fullBuilder.add("meta", metaBuilder.build());
                } else {
                    fullBuilder = fullBuilder.add(keyStr, currentYaml.value(keyStr));
                }
            }

            // Write updated YAML atomically
            final YamlMapping updatedYaml = fullBuilder.build();
            Files.writeString(
                this.configPath,
                updatedYaml.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (final Exception ex) {
            throw new IOException(
                String.format(
                    "Failed to persist prefixes to %s: %s",
                    this.configPath,
                    ex.getMessage()
                ),
                ex
            );
        }
    }
}
