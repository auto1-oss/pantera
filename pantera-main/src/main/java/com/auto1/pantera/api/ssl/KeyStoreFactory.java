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
package com.auto1.pantera.api.ssl;

import com.amihaiemil.eoyaml.YamlMapping;
import java.util.List;

/**
 * KeyStore factory.
 * @since 0.26
 */
public final class KeyStoreFactory {
    /**
     * Ctor.
     */
    private KeyStoreFactory() {
    }

    /**
     * Create KeyStore instance.
     * @param yaml Settings of key store
     * @return KeyStore
     */
    public static KeyStore newInstance(final YamlMapping yaml) {
        final List<KeyStore> keystores = List.of(
            new JksKeyStore(yaml), new PemKeyStore(yaml), new PfxKeyStore(yaml)
        );
        for (final KeyStore keystore : keystores) {
            if (keystore.isConfigured()) {
                return keystore;
            }
        }
        throw new IllegalStateException("Not found configuration in 'ssl'-section of yaml");
    }
}
