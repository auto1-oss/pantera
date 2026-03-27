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
package com.auto1.pantera.security.policy;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.asto.factory.Config;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Policy configuration.
 * @since 1.2
 */
public final class YamlPolicyConfig implements Config {

    /**
     * Yaml mapping source to read config from.
     */
    private final YamlMapping source;

    /**
     * Ctor.
     * @param yaml Yaml mapping source to read config from
     */
    public YamlPolicyConfig(final YamlMapping yaml) {
        this.source = yaml;
    }

    @Override
    public String string(final String key) {
        return this.source.string(key);
    }

    @Override
    public Collection<String> sequence(final String key) {
        return Optional.ofNullable(this.source.yamlSequence(key))
            .map(
                seq -> seq.values().stream()
                    .map(item -> item.asScalar().value()).collect(Collectors.toList())
            ).orElse(Collections.emptyList());
    }

    @Override
    public YamlPolicyConfig config(final String key) {
        return new YamlPolicyConfig(this.source.yamlMapping(key));
    }

    @Override
    public boolean isEmpty() {
        return this.source == null || this.source.isEmpty();
    }

    @Override
    public String toString() {
        return this.source.toString();
    }

}
