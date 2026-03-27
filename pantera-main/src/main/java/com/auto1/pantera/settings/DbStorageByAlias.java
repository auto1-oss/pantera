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
import com.amihaiemil.eoyaml.YamlMappingBuilder;
import com.auto1.pantera.misc.Json2Yaml;
import java.util.List;
import javax.json.JsonObject;

/**
 * Build a {@link StorageByAlias} from database storage_aliases records.
 * @since 1.21
 */
public final class DbStorageByAlias {

    private DbStorageByAlias() {
    }

    /**
     * Create a {@link StorageByAlias} from database alias records.
     * @param aliases List of alias objects from StorageAliasDao
     * @return StorageByAlias backed by DB data
     */
    public static StorageByAlias from(final List<JsonObject> aliases) {
        final Json2Yaml converter = new Json2Yaml();
        YamlMappingBuilder storages = Yaml.createYamlMappingBuilder();
        for (final JsonObject alias : aliases) {
            final String name = alias.getString("name");
            final JsonObject config = alias.getJsonObject("config");
            storages = storages.add(name, converter.apply(config.toString()));
        }
        return new StorageByAlias(
            Yaml.createYamlMappingBuilder()
                .add("storages", storages.build())
                .build()
        );
    }
}
