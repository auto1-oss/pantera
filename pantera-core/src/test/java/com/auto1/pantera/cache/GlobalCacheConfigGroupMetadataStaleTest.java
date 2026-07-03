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
package com.auto1.pantera.cache;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GlobalCacheConfig#groupMetadataStale()}.
 * Verifies env → YAML → default resolution order.
 *
 * @since 2.2.0
 */
final class GlobalCacheConfigGroupMetadataStaleTest {

    /**
     * ConfigDefaults maps env-var {@code FOO_BAR_BAZ} to sysprop
     * {@code foo.bar.baz}. We set sysprops in tests and clear them after.
     */
    private static final String PROP_L1_SIZE =
        "pantera.group.metadata.stale.l1.size";
    private static final String PROP_L1_TTL =
        "pantera.group.metadata.stale.l1.ttl.seconds";
    private static final String PROP_L2_ENABLED =
        "pantera.group.metadata.stale.l2.enabled";
    private static final String PROP_L2_TTL =
        "pantera.group.metadata.stale.l2.ttl.seconds";
    private static final String PROP_L2_TIMEOUT =
        "pantera.group.metadata.stale.l2.timeout.ms";

    @BeforeEach
    void setUp() {
        GlobalCacheConfig.reset();
        clearSysProps();
    }

    @AfterEach
    void tearDown() {
        GlobalCacheConfig.reset();
        clearSysProps();
    }

    private void clearSysProps() {
        System.clearProperty(PROP_L1_SIZE);
        System.clearProperty(PROP_L1_TTL);
        System.clearProperty(PROP_L2_ENABLED);
        System.clearProperty(PROP_L2_TTL);
        System.clearProperty(PROP_L2_TIMEOUT);
    }

    @Test
    void defaultValuesMatchConstants() {
        GlobalCacheConfig.initialize(Optional.empty(), null);
        final GlobalCacheConfig.GroupMetadataStaleConfig cfg =
            GlobalCacheConfig.getInstance().groupMetadataStale();
        Assertions.assertEquals(
            GlobalCacheConfig.DEFAULT_GROUP_METADATA_STALE_L1_MAX_SIZE,
            cfg.l1MaxSize()
        );
        Assertions.assertEquals(
            GlobalCacheConfig.DEFAULT_GROUP_METADATA_STALE_L1_TTL_SECONDS,
            cfg.l1TtlSeconds()
        );
        Assertions.assertEquals(
            GlobalCacheConfig.DEFAULT_GROUP_METADATA_STALE_L2_ENABLED,
            cfg.l2Enabled()
        );
        Assertions.assertEquals(
            GlobalCacheConfig.DEFAULT_GROUP_METADATA_STALE_L2_TTL_SECONDS,
            cfg.l2TtlSeconds()
        );
        Assertions.assertEquals(
            GlobalCacheConfig.DEFAULT_GROUP_METADATA_STALE_L2_TIMEOUT_MS,
            cfg.l2TimeoutMs()
        );
    }

    @Test
    void yamlOverridesDefaults() throws IOException {
        final String yaml = String.join("\n",
            "caches:",
            "  group-metadata-stale:",
            "    l1:",
            "      maxSize: 42",
            "      ttlSeconds: 1234",
            "    l2:",
            "      enabled: false",
            "      ttlSeconds: 60",
            "      timeoutMs: 250"
        );
        final YamlMapping caches = Yaml.createYamlInput(yaml).readYamlMapping()
            .yamlMapping("caches");
        GlobalCacheConfig.initialize(Optional.empty(), caches);
        final GlobalCacheConfig.GroupMetadataStaleConfig cfg =
            GlobalCacheConfig.getInstance().groupMetadataStale();
        Assertions.assertEquals(42, cfg.l1MaxSize());
        Assertions.assertEquals(1234, cfg.l1TtlSeconds());
        Assertions.assertFalse(cfg.l2Enabled());
        Assertions.assertEquals(60, cfg.l2TtlSeconds());
        Assertions.assertEquals(250, cfg.l2TimeoutMs());
    }

    @Test
    void envVarTrumpsYamlAndDefault() throws IOException {
        final String yaml = String.join("\n",
            "caches:",
            "  group-metadata-stale:",
            "    l1:",
            "      maxSize: 42",
            "      ttlSeconds: 1234"
        );
        final YamlMapping caches = Yaml.createYamlInput(yaml).readYamlMapping()
            .yamlMapping("caches");
        // Sysprop shape = lowercased env-var with '_' -> '.'.
        System.setProperty(PROP_L1_SIZE, "999");
        System.setProperty(PROP_L1_TTL, "5555");
        System.setProperty(PROP_L2_ENABLED, "false");
        System.setProperty(PROP_L2_TTL, "777");
        System.setProperty(PROP_L2_TIMEOUT, "88");
        GlobalCacheConfig.initialize(Optional.empty(), caches);
        final GlobalCacheConfig.GroupMetadataStaleConfig cfg =
            GlobalCacheConfig.getInstance().groupMetadataStale();
        Assertions.assertEquals(999, cfg.l1MaxSize());
        Assertions.assertEquals(5555, cfg.l1TtlSeconds());
        Assertions.assertFalse(cfg.l2Enabled());
        Assertions.assertEquals(777, cfg.l2TtlSeconds());
        Assertions.assertEquals(88, cfg.l2TimeoutMs());
    }
}
