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

package com.auto1.pantera.adapters.php;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMappingBuilder;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.settings.StorageByAlias;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.test.TestStoragesCache;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@code url:} is optional for {@code php-proxy}: served links are rooted at
 * the base resolved per request, so a proxy configured without one must
 * still construct (it used to throw "yaml repo.url is absent", which through
 * a group surfaced as a 503 for every package the hosted member lacks).
 */
final class ComposerProxyUrlOptionalTest {

    @Test
    void constructsWithoutConfiguredUrl() {
        Assertions.assertDoesNotThrow(
            () -> new ComposerProxy(
                new NoopClientSlices(),
                ComposerProxyUrlOptionalTest.config(Optional.empty()),
                Optional.empty(),
                NoopCooldownService.INSTANCE
            )
        );
    }

    @Test
    void constructsWithConfiguredUrl() {
        Assertions.assertDoesNotThrow(
            () -> new ComposerProxy(
                new NoopClientSlices(),
                ComposerProxyUrlOptionalTest.config(Optional.of("http://pantera:8080/php_proxy")),
                Optional.empty(),
                NoopCooldownService.INSTANCE
            )
        );
    }

    /**
     * A php-proxy configuration with one remote.
     *
     * @param url Configured url, or empty
     * @return Repo configuration
     */
    private static RepoConfig config(final Optional<String> url) {
        YamlMappingBuilder repo = Yaml.createYamlMappingBuilder()
            .add("type", "php-proxy")
            .add(
                "remotes",
                Yaml.createYamlSequenceBuilder().add(
                    Yaml.createYamlMappingBuilder()
                        .add("url", "https://repo.packagist.org")
                        .build()
                ).build()
            )
            .add(
                "storage",
                Yaml.createYamlMappingBuilder()
                    .add("type", "fs")
                    .add("path", "/var/pantera/data")
                    .build()
            );
        if (url.isPresent()) {
            repo = repo.add("url", url.get());
        }
        return RepoConfig.from(
            Yaml.createYamlMappingBuilder().add("repo", repo.build()).build(),
            new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
            new Key.From("php_proxy"), new TestStoragesCache(), false
        );
    }

    /**
     * Client slices never exercised by construction-only tests.
     */
    private static final class NoopClientSlices implements ClientSlices {

        @Override
        public Slice http(final String host) {
            throw new UnsupportedOperationException("not exercised by construction-only tests");
        }

        @Override
        public Slice http(final String host, final int port) {
            throw new UnsupportedOperationException("not exercised by construction-only tests");
        }

        @Override
        public Slice https(final String host) {
            throw new UnsupportedOperationException("not exercised by construction-only tests");
        }

        @Override
        public Slice https(final String host, final int port) {
            throw new UnsupportedOperationException("not exercised by construction-only tests");
        }
    }
}
