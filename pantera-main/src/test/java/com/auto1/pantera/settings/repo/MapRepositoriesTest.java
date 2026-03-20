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
package com.auto1.pantera.settings.repo;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.settings.AliasSettings;
import com.auto1.pantera.settings.Settings;
import com.auto1.pantera.test.TestSettings;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.nio.file.Path;
import java.util.NoSuchElementException;

/**
 * Tests for cache of files with configuration in {@link MapRepositories}.
 */
final class MapRepositoriesTest {

    /**
     * Repo name.
     */
    private static final String REPO = "my-repo";

    /**
     * Type repository.
     */
    private static final String TYPE = "maven";

    private Storage storage;

    private Settings settings;

    @BeforeEach
    void setUp() {
        this.settings = new TestSettings();
        this.storage = this.settings.repoConfigsStorage();
    }

    @ParameterizedTest
    @CsvSource({"_storages.yaml", "_storages.yml"})
    void findRepoSettingAndCreateRepoConfigWithStorageAlias(final String filename) {
        final String alias = "default";
        new RepoConfigYaml(MapRepositoriesTest.TYPE)
            .withStorageAlias(alias)
            .saveTo(this.storage, MapRepositoriesTest.REPO);
        this.saveAliasConfig(alias, filename);
        Assertions.assertTrue(this.repoConfig().storageOpt().isPresent());
    }

    @Test
    void findRepoSettingAndCreateRepoConfigWithCustomStorage() {
        new RepoConfigYaml(MapRepositoriesTest.TYPE)
            .withFileStorage(Path.of("some", "somepath"))
            .saveTo(this.storage, MapRepositoriesTest.REPO);
        Assertions.assertTrue(this.repoConfig().storageOpt().isPresent());
    }

    @Test
    void throwsExceptionWhenConfigYamlAbsent() {
        Assertions.assertTrue(
            new MapRepositories(this.settings, Duration.ZERO)
                .config(MapRepositoriesTest.REPO)
                .isEmpty()
        );
    }

    @Test
    void throwsExceptionWhenConfigYamlMalformedSinceWithoutStorage() {
        new RepoConfigYaml(MapRepositoriesTest.TYPE)
            .saveTo(this.storage, MapRepositoriesTest.REPO);
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> this.repoConfig().storage()
        );
    }

    @Test
    void throwsExceptionWhenAliasesConfigAbsent() {
        new RepoConfigYaml(MapRepositoriesTest.TYPE)
            .withStorageAlias("alias")
            .saveTo(this.storage, MapRepositoriesTest.REPO);
        Assertions.assertThrows(NoSuchElementException.class, this::repoConfig);
    }

    @Test
    void throwsExceptionWhenAliasConfigMalformedSinceSequenceInsteadMapping() {
        final String alias = "default";
        new RepoConfigYaml(MapRepositoriesTest.TYPE)
            .withStorageAlias(alias)
            .saveTo(this.storage, MapRepositoriesTest.REPO);
        this.storage.save(
            new Key.From(AliasSettings.FILE_NAME),
            new Content.From(
                Yaml.createYamlMappingBuilder().add(
                    "storages", Yaml.createYamlSequenceBuilder()
                        .add(
                            Yaml.createYamlMappingBuilder().add(
                                alias, Yaml.createYamlMappingBuilder()
                                    .add("type", "fs")
                                    .add("path", "/some/path")
                                    .build()
                            ).build()
                        ).build()
                ).build().toString().getBytes()
            )
        ).join();
        Assertions.assertThrows(NoSuchElementException.class, this::repoConfig);
    }

    @Test
    void throwsExceptionForUnknownAlias() {
        this.saveAliasConfig("some alias", AliasSettings.FILE_NAME);
        new RepoConfigYaml(MapRepositoriesTest.TYPE)
            .withStorageAlias("unknown alias")
            .saveTo(this.storage, MapRepositoriesTest.REPO);
        Assertions.assertThrows(NoSuchElementException.class, this::repoConfig);
    }

    @Test
    void readFromCacheAndRefreshCacheData() {
        Key key = new Key.From("some-repo.yaml");
        new BlockingStorage(this.settings.repoConfigsStorage())
            .save(key, "repo:\n  type: old_type".getBytes());
        Repositories repos = new MapRepositories(this.settings, Duration.ZERO);
        new BlockingStorage(this.settings.repoConfigsStorage())
            .save(key, "repo:\n  type: new_type".getBytes());

        Assertions.assertEquals("old_type",
            repos.config(key.string()).orElseThrow().type());

        repos.refreshAsync().join();

        Assertions.assertEquals("new_type",
            repos.config(key.string()).orElseThrow().type());
    }

    @Test
    void readAliasesFromCacheAndRefreshCache() {
        final Key alias = new Key.From("_storages.yaml");
        new TestResource(alias.string()).saveTo(this.settings.repoConfigsStorage());
        Key config = new Key.From("bin.yaml");
        BlockingStorage cfgStorage = new BlockingStorage(this.settings.repoConfigsStorage());
        cfgStorage.save(config, "repo:\n  type: maven\n  storage: default".getBytes());
        Repositories repo = new MapRepositories(this.settings, Duration.ZERO);
        cfgStorage.save(config, "repo:\n  type: maven".getBytes());

        Assertions.assertTrue(
            repo.config(config.string())
                .orElseThrow().storageOpt().isPresent()
        );

        repo.refreshAsync().join();

        Assertions.assertTrue(
            repo.config(config.string())
                .orElseThrow().storageOpt().isEmpty()
        );
    }

    private RepoConfig repoConfig() {
        return new MapRepositories(this.settings, Duration.ZERO)
            .config(MapRepositoriesTest.REPO)
            .orElseThrow();
    }

    private void saveAliasConfig(final String alias, final String filename) {
        this.storage.save(
            new Key.From(filename),
            new Content.From(
                Yaml.createYamlMappingBuilder().add(
                    "storages", Yaml.createYamlMappingBuilder()
                        .add(
                            alias, Yaml.createYamlMappingBuilder()
                                .add("type", "fs")
                                .add("path", "/some/path")
                                .build()
                        ).build()
                ).build().toString().getBytes()
            )
        ).join();
    }

    @Test
    void refreshAsyncDoesNotBlockThreadPool() throws Exception {
        // Create multiple repository configs to test parallel loading
        final int repoCount = 10;
        for (int i = 0; i < repoCount; i++) {
            new RepoConfigYaml(MapRepositoriesTest.TYPE)
                .withFileStorage(Path.of("test", "path" + i))
                .saveTo(this.storage, "repo" + i);
        }

        final MapRepositories repos = new MapRepositories(this.settings, Duration.ZERO);

        // Verify refreshAsync completes without blocking
        // If it blocks the ForkJoinPool, this will timeout
        repos.refreshAsync().get(5, java.util.concurrent.TimeUnit.SECONDS);

        // Verify all repos were loaded
        Assertions.assertEquals(
            repoCount,
            repos.configs().size(),
            "All repositories should be loaded"
        );
    }
}
