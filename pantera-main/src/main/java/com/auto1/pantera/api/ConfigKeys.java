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
package com.auto1.pantera.api;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.settings.ConfigFile;
import java.util.List;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Provides yaml and yml settings keys for given name.
 * @since 0.26
 */
public class ConfigKeys {
    /**
     * A pair of keys, these keys are possible settings names.
     */
    private final Pair<Key, Key> pair;

    /**
     * Ctor.
     * @param name Key name
     */
    public ConfigKeys(final String name) {
        this.pair = new ImmutablePair<>(
            new Key.From(String.format("%s.yaml", name)),
            new Key.From(String.format("%s.yml", name))
        );
    }

    /**
     * Key for setting name with '.yaml' extension.
     * @return Key
     */
    public Key yamlKey() {
        return this.pair.getLeft();
    }

    /**
     * Key for setting name with '.yml' extension.
     * @return Key
     */
    public Key ymlKey() {
        return this.pair.getRight();
    }

    /**
     * Key for setting name by YAML-extension.
     * @param ext Extension
     * @return Key
     */
    public Key key(final ConfigFile.Extension ext) {
        final Key key;
        if (ext == ConfigFile.Extension.YAML) {
            key = this.yamlKey();
        } else {
            key = this.ymlKey();
        }
        return key;
    }

    /**
     * List of yaml-keys for setting name.
     * @return List of keys
     */
    public List<Key> keys() {
        return List.of(this.yamlKey(), this.ymlKey());
    }
}
