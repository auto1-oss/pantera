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
package com.auto1.pantera.index.reindex;

import com.auto1.pantera.api.RepositoryName;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.misc.DispatchedStorage;
import com.auto1.pantera.settings.repo.CrudRepoSettings;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonStructure;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link CrudReindexRepos}.
 *
 * @since 2.2.9
 */
final class CrudReindexReposTest {

    @Test
    void resolvesFileStorageRootOfRepository(@TempDir final Path dir) {
        final Storage storage = new SubStorage(
            new Key.From("maven-local"), new DispatchedStorage(new FileStorage(dir))
        );
        final ReindexRepos.Target target = new CrudReindexRepos(
            new Settings(Map.of("maven-local", CrudReindexReposTest.wrapped("maven"))),
            name -> Optional.of(storage)
        ).target("maven-local");
        MatcherAssert.assertThat(
            "Root is the repository's directory under the storage path",
            target.root(), new IsEqual<>(dir.resolve("maven-local"))
        );
        MatcherAssert.assertThat("Type is read", target.type(), new IsEqual<>("maven"));
        MatcherAssert.assertThat("Not skipped", target.skip(), new IsNull<>());
    }

    @Test
    void readsTypeOfUnwrappedConfig(@TempDir final Path dir) {
        MatcherAssert.assertThat(
            new CrudReindexRepos(
                new Settings(
                    Map.of("npm", Json.createObjectBuilder().add("type", "npm-proxy").build())
                ),
                name -> Optional.of(new FileStorage(dir))
            ).target("npm").type(),
            new IsEqual<>("npm-proxy")
        );
    }

    @Test
    void skipsStorageWithoutLocalPath() {
        MatcherAssert.assertThat(
            new CrudReindexRepos(
                new Settings(Map.of("s3", CrudReindexReposTest.wrapped("maven"))),
                name -> Optional.of(new DispatchedStorage(new InMemoryStorage()))
            ).target("s3").skip(),
            new IsEqual<>("storage is not on the local file system (e.g. S3)")
        );
    }

    @Test
    void skipsGroupRepository() {
        MatcherAssert.assertThat(
            new CrudReindexRepos(
                new Settings(Map.of("g", CrudReindexReposTest.wrapped("maven-group"))),
                name -> Optional.empty()
            ).target("g").skip(),
            new IsEqual<>("group repositories have no storage of their own")
        );
    }

    @Test
    void skipsRepositoryWithoutStorage() {
        MatcherAssert.assertThat(
            new CrudReindexRepos(
                new Settings(Map.of("x", CrudReindexReposTest.wrapped("file"))),
                name -> Optional.empty()
            ).target("x").skip(),
            new IsEqual<>("repository has no storage configured")
        );
    }

    /**
     * Stored config wrapped in a {@code repo} object.
     * @param type Repository type
     * @return JSON
     */
    private static JsonObject wrapped(final String type) {
        return Json.createObjectBuilder()
            .add("repo", Json.createObjectBuilder().add("type", type))
            .build();
    }

    /**
     * Read-only repository settings over a map.
     * @since 2.2.9
     */
    private static final class Settings implements CrudRepoSettings {

        /**
         * Configs by name.
         */
        private final Map<String, JsonObject> configs;

        Settings(final Map<String, JsonObject> configs) {
            this.configs = configs;
        }

        @Override
        public Collection<String> listAll() {
            return List.copyOf(this.configs.keySet());
        }

        @Override
        public Collection<String> list(final String uname) {
            return this.listAll();
        }

        @Override
        public boolean exists(final RepositoryName rname) {
            return this.configs.containsKey(rname.toString());
        }

        @Override
        public JsonStructure value(final RepositoryName name) {
            return this.configs.get(name.toString());
        }

        @Override
        public void save(final RepositoryName rname, final JsonStructure value) {
            throw new UnsupportedOperationException("read-only");
        }

        @Override
        public void delete(final RepositoryName rname) {
            throw new UnsupportedOperationException("read-only");
        }

        @Override
        public void move(final RepositoryName rname, final RepositoryName newrname) {
            throw new UnsupportedOperationException("read-only");
        }

        @Override
        public boolean hasSettingsDuplicates(final RepositoryName rname) {
            return false;
        }
    }
}
