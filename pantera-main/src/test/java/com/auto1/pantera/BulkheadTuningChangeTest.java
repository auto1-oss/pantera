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
package com.auto1.pantera;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.resilience.RepoBulkhead;
import com.auto1.pantera.http.resilience.RepoBulkheadRegistry;
import com.auto1.pantera.settings.StorageByAlias;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.settings.repo.Repositories;
import com.auto1.pantera.settings.runtime.BulkheadTuning;
import com.auto1.pantera.test.TestSettings;
import com.auto1.pantera.test.TestStoragesCache;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A change of the {@code http_client.bulkhead.*} settings replaces the
 * bulkhead of every proxy repository at once. A repository serves through a
 * cached slice that finds its bulkhead by name on each request: dropping the
 * bulkhead without a replacement left the repository without any limit until
 * its slice was rebuilt, and its permit gauge on the old value.
 *
 * @since 2.2.12
 */
final class BulkheadTuningChangeTest {

    /**
     * Proxy repository name. Unique to this class: the registry is JVM-wide.
     */
    private static final String REPO = "bulkhead-tuning-change-proxy";

    @Test
    void settingsChangeReplacesTheBulkheadOfARepositoryThatIsAlreadyServing(
        @TempDir final Path tmp
    ) {
        final RepositorySlices slices = new RepositorySlices(
            new TestSettings(), new SingleRepo(BulkheadTuningChangeTest.proxy(tmp)), new NoTokens()
        );
        slices.slice(new Key.From(BulkheadTuningChangeTest.REPO), 8080);
        final Optional<RepoBulkhead> before =
            RepoBulkheadRegistry.instance().bulkheadFor(BulkheadTuningChangeTest.REPO);
        slices.setBulkheadTuningSupplier(
            () -> new BulkheadTuning(false, 5, 300, 300, 500L, 5, 4, 0.5)
        );
        slices.invalidateBulkheads();
        final Optional<RepoBulkhead> after =
            RepoBulkheadRegistry.instance().bulkheadFor(BulkheadTuningChangeTest.REPO);
        MatcherAssert.assertThat(
            "the repository had a bulkhead with the default permits",
            before.map(RepoBulkhead::currentPermits),
            new IsEqual<>(Optional.of(BulkheadTuning.defaults().initialPermits()))
        );
        MatcherAssert.assertThat(
            "after the change it has one with the new permits, without its slice being rebuilt",
            after.map(RepoBulkhead::currentPermits),
            new IsEqual<>(Optional.of(300))
        );
    }

    /**
     * File proxy repository config.
     * @param dir Storage dir
     * @return Config
     */
    private static RepoConfig proxy(final Path dir) {
        return RepoConfig.from(
            Yaml.createYamlMappingBuilder().add(
                "repo",
                Yaml.createYamlMappingBuilder()
                    .add("type", "file-proxy")
                    .add(
                        "storage",
                        Yaml.createYamlMappingBuilder()
                            .add("type", "fs")
                            .add("path", dir.toString())
                            .build()
                    )
                    .add(
                        "remotes",
                        Yaml.createYamlSequenceBuilder().add(
                            Yaml.createYamlMappingBuilder()
                                .add("url", "http://127.0.0.1:9").build()
                        ).build()
                    )
                    .build()
            ).build(),
            new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
            new Key.From(BulkheadTuningChangeTest.REPO),
            new TestStoragesCache(),
            false
        );
    }

    /**
     * One repository.
     */
    private static final class SingleRepo implements Repositories {

        /**
         * The config.
         */
        private final RepoConfig cfg;

        /**
         * Ctor.
         * @param cfg The config
         */
        SingleRepo(final RepoConfig cfg) {
            this.cfg = cfg;
        }

        @Override
        public Optional<RepoConfig> config(final String name) {
            return Optional.of(this.cfg).filter(one -> one.name().equals(name));
        }

        @Override
        public Collection<RepoConfig> configs() {
            return List.of(this.cfg);
        }
    }

    /**
     * Token service that knows no token.
     */
    private static final class NoTokens implements Tokens {

        @Override
        public TokenAuthentication auth() {
            return token -> CompletableFuture.completedFuture(Optional.<AuthUser>empty());
        }

        @Override
        public String generate(final AuthUser user) {
            return "token-for-" + user.name();
        }
    }
}
