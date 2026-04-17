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
package com.auto1.pantera.test;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlSequence;
import com.auto1.pantera.api.ssl.KeyStore;
import com.auto1.pantera.api.ssl.KeyStoreFactory;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.auth.AuthFromEnv;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.scheduling.MetadataEventQueues;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.PanteraSecurity;
import com.auto1.pantera.settings.LoggingContext;
import com.auto1.pantera.settings.MetricsContext;
import com.auto1.pantera.settings.PrefixesConfig;
import com.auto1.pantera.settings.Settings;
import com.auto1.pantera.settings.cache.PanteraCaches;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Test {@link Settings} implementation.
 *
 * @since 0.2
 */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class TestSettings implements Settings {

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Yaml `meta` mapping.
     */
    private final YamlMapping meta;

    /**
     * Test caches.
     */
    private final PanteraCaches caches;

    /**
     * Ctor.
     */
    public TestSettings() {
        this(new InMemoryStorage());
    }

    /**
     * Ctor.
     *
     * @param storage Storage
     */
    public TestSettings(final Storage storage) {
        this(
            storage,
            Yaml.createYamlMappingBuilder().build()
        );
    }

    /**
     * Ctor.
     *
     * @param meta Yaml `meta` mapping
     */
    public TestSettings(final YamlMapping meta) {
        this(new InMemoryStorage(), meta);
    }

    /**
     * Primary ctor.
     *
     * @param storage Storage
     * @param meta Yaml `meta` mapping
     */
    public TestSettings(
        final Storage storage,
        final YamlMapping meta
    ) {
        this.storage = storage;
        this.meta = meta;
        this.caches = new TestPanteraCaches();
    }

    @Override
    public Storage configStorage() {
        return this.storage;
    }

    @Override
    public PanteraSecurity authz() {
        return new PanteraSecurity() {
            @Override
            public Authentication authentication() {
                return new AuthFromEnv();
            }

            @Override
            public Policy<?> policy() {
                return Policy.FREE;
            }

            @Override
            public Optional<Storage> policyStorage() {
                return Optional.empty();
            }
        };
    }

    @Override
    public YamlMapping meta() {
        return this.meta;
    }

    @Override
    public Storage repoConfigsStorage() {
        return this.storage;
    }

    @Override
    public Optional<KeyStore> keyStore() {
        return Optional.ofNullable(this.meta().yamlMapping("ssl"))
            .map(KeyStoreFactory::newInstance);
    }

    @Override
    public MetricsContext metrics() {
        return new MetricsContext(Yaml.createYamlMappingBuilder().build());
    }

    @Override
    public PanteraCaches caches() {
        return this.caches;
    }

    @Override
    public Optional<MetadataEventQueues> artifactMetadata() {
        return Optional.empty();
    }

    @Override
    public Optional<YamlSequence> crontab() {
        return Optional.empty();
    }

    @Override
    public LoggingContext logging() {
        return new LoggingContext(Yaml.createYamlMappingBuilder().build());
    }

    @Override
    public CooldownSettings cooldown() {
        return CooldownSettings.defaults();
    }

    @Override
    public Optional<DataSource> artifactsDatabase() {
        return Optional.empty();
    }

    @Override
    public PrefixesConfig prefixes() {
        return new PrefixesConfig();
    }

    @Override
    public java.nio.file.Path configPath() {
        return java.nio.file.Paths.get("/tmp/test-pantera.yaml");
    }
}
