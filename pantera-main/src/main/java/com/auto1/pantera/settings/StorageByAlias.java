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

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.cache.StoragesCache;

/**
 * Obtain storage by alias from aliases settings yaml.
 * @since 0.4
 */
public final class StorageByAlias {

    /**
     * Aliases yaml.
     */
    private final YamlMapping yaml;

    /**
     * Aliases from yaml.
     * @param yaml Yaml
     */
    public StorageByAlias(final YamlMapping yaml) {
        this.yaml = yaml;
    }

    /**
     * Get storage by alias.
     * @param cache Storage cache
     * @param alias Storage alias
     * @return Storage instance
     */
    public Storage storage(final StoragesCache cache, final String alias) {
        final YamlMapping mapping = this.yaml.yamlMapping("storages");
        if (mapping != null) {
            final YamlMapping aliasMapping = mapping.yamlMapping(alias);
            if (aliasMapping != null) {
                return cache.storage(aliasMapping);
            }
        }
        throw new IllegalStateException(
            String.format(
                "yaml file with aliases is malformed or alias `%s` is absent",
                alias
            )
        );
    }
}
