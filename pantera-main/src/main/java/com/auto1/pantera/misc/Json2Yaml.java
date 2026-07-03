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
package com.auto1.pantera.misc;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Function;

/**
 * Convert json string to {@link YamlMapping}.
 * @since 0.1
 */
public final class Json2Yaml implements Function<String, YamlMapping> {

    /**
     * JSON reader — thread-safe once configured; hoisted to avoid per-call allocation.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * YAML writer configured once with INDENT_ARRAYS_WITH_INDICATOR;
     * thread-safe for write operations; hoisted to avoid per-call allocation.
     */
    private static final YAMLMapper YAML = new YAMLMapper()
        .configure(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR, true);

    @Override
    public YamlMapping apply(final String json) {
        try {
            return Yaml.createYamlInput(
                Json2Yaml.YAML.writeValueAsString(Json2Yaml.JSON.readTree(json))
            ).readYamlMapping();
        } catch (final IOException err) {
            throw new UncheckedIOException(err);
        }
    }
}
