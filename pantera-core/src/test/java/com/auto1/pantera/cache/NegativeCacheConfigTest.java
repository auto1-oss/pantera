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
import java.time.Duration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NegativeCacheConfig#fromYaml(YamlMapping, String)}.
 * Verifies the sub-key loader used by {@code RepositorySlices} to parse
 * {@code meta.caches.repo-negative} (and the deprecated legacy alias
 * {@code group-negative}).
 *
 * @since 2.1.3
 */
final class NegativeCacheConfigTest {

    @Test
    void parsesSubKeyWithValkeyEnabled() throws IOException {
        final String yaml = String.join("\n",
            "caches:",
            "  group-negative:",
            "    ttl: 10m",
            "    maxSize: 2000",
            "    valkey:",
            "      enabled: true",
            "      l1MaxSize: 500",
            "      l1Ttl: 2m",
            "      l2MaxSize: 100000",
            "      l2Ttl: 1h"
        );
        final YamlMapping caches = Yaml.createYamlInput(yaml).readYamlMapping()
            .yamlMapping("caches");
        final NegativeCacheConfig cfg = NegativeCacheConfig.fromYaml(caches, "group-negative");
        Assertions.assertEquals(Duration.ofMinutes(10), cfg.ttl());
        Assertions.assertEquals(2000, cfg.maxSize());
        Assertions.assertTrue(cfg.isValkeyEnabled());
        Assertions.assertEquals(500, cfg.l1MaxSize());
        Assertions.assertEquals(Duration.ofMinutes(2), cfg.l1Ttl());
        Assertions.assertEquals(100_000, cfg.l2MaxSize());
        Assertions.assertEquals(Duration.ofHours(1), cfg.l2Ttl());
    }

    @Test
    void returnsDefaultsWhenSubKeyAbsent() throws IOException {
        final String yaml = String.join("\n",
            "caches:",
            "  negative:",
            "    ttl: 24h",
            "    maxSize: 5000"
        );
        final YamlMapping caches = Yaml.createYamlInput(yaml).readYamlMapping()
            .yamlMapping("caches");
        final NegativeCacheConfig cfg = NegativeCacheConfig.fromYaml(caches, "group-negative");
        Assertions.assertEquals(NegativeCacheConfig.DEFAULT_TTL, cfg.ttl());
        Assertions.assertEquals(NegativeCacheConfig.DEFAULT_MAX_SIZE, cfg.maxSize());
        Assertions.assertFalse(cfg.isValkeyEnabled());
    }

    @Test
    void returnsDefaultsWhenCachesNull() {
        final NegativeCacheConfig cfg = NegativeCacheConfig.fromYaml(null, "group-negative");
        Assertions.assertEquals(NegativeCacheConfig.DEFAULT_TTL, cfg.ttl());
        Assertions.assertFalse(cfg.isValkeyEnabled());
    }

}
