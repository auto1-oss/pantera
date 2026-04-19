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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Function;
import javax.json.Json;
import javax.json.JsonStructure;

/**
 * Transform yaml to json.
 * @since 0.1
 */
public final class Yaml2Json implements Function<String, JsonStructure> {

    /**
     * JSON writer — thread-safe once configured; hoisted to avoid per-call allocation.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * YAML reader — thread-safe once configured; hoisted to avoid per-call allocation.
     */
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Override
    public JsonStructure apply(final String yaml) {
        try {
            return Json.createReader(
                new ByteArrayInputStream(
                    Yaml2Json.JSON.writeValueAsBytes(
                        Yaml2Json.YAML
                            .readValue(Yaml2Json.escapeAsterisk(yaml), Object.class)
                    )
                )
            ).read();
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * EO yaml {@link com.amihaiemil.eoyaml} does always escapes * while
     * transforming {@link com.amihaiemil.eoyaml.YamlMapping} into string.
     * {@link com.fasterxml.jackson.dataformat.yaml.YAMLFactory} does not tolerate it,
     * so we have to escape.
     * Asterisk can be met in permissions as item of yaml sequence:
     * Jane:
     *   - *
     * which means that Jane is allowed to perform any actions.
     * And this "- *" is what we will escape.
     * @param yaml Yaml string
     * @return Yaml string with escaped asterisk
     */
    private static String escapeAsterisk(final String yaml) {
        return yaml.replace("- *", "- \"*\"");
    }
}
