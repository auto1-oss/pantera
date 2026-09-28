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
package com.auto1.pantera.api.v1;

import com.auto1.pantera.api.RepositoryName;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.pypi.meta.PypiSidecar;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.repo.CrudRepoSettings;
import com.auto1.pantera.test.TestStoragesCache;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonStructure;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link DbRepoStorage} resolves a DB-configured repository's storage the
 * way the running server does — including a storage given as a string alias
 * ({@code storage: "default"}), which used to fail the PyPI yank endpoint
 * with a ClassCastException (B11).
 *
 * @since 2.2.9
 */
final class DbRepoStorageTest {

    /**
     * Repository name.
     */
    private static final String REPO = "pypi";

    @TempDir
    private Path root;

    @Test
    void resolvesStorageGivenAsAlias() throws Exception {
        final Optional<Storage> storage = this.resolver(Json.createValue("default"))
            .apply(REPO);
        storage.orElseThrow().save(
            new Key.From("a.txt"), new Content.From("x".getBytes(StandardCharsets.UTF_8))
        ).join();
        MatcherAssert.assertThat(
            Files.exists(this.root.resolve(REPO).resolve("a.txt")),
            new IsEqual<>(true)
        );
    }

    @Test
    void resolvesInlineStorage() throws Exception {
        final Optional<Storage> storage = this.resolver(this.fsConfig()).apply(REPO);
        storage.orElseThrow().save(
            new Key.From("b.txt"), new Content.From("y".getBytes(StandardCharsets.UTF_8))
        ).join();
        MatcherAssert.assertThat(
            Files.exists(this.root.resolve(REPO).resolve("b.txt")),
            new IsEqual<>(true)
        );
    }

    @Test
    void unknownRepositoryHasNoStorage() {
        MatcherAssert.assertThat(
            this.resolver(Json.createValue("default")).apply("no_such_repo").isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void yankOnAliasBackedRepositoryIsApplied() {
        final DbRepoStorage resolver = this.resolver(Json.createValue("default"));
        final Storage storage = resolver.apply(REPO).orElseThrow();
        final Key key = new Key.From("qa-pkg", "0.0.1", "qa_pkg-0.0.1.tar.gz");
        storage.save(key, new Content.From("z".getBytes(StandardCharsets.UTF_8))).join();
        PypiSidecar.write(storage, key, null, Instant.parse("2026-01-01T00:00:00Z")).join();
        MatcherAssert.assertThat(
            new PypiHandler(Policy.FREE, resolver)
                .lifecycle(REPO, "qa-pkg", "0.0.1", true, "bad"),
            new IsEqual<>(PypiHandler.Outcome.APPLIED)
        );
    }

    private JsonObject fsConfig() {
        return Json.createObjectBuilder()
            .add("type", "fs")
            .add("path", this.root.toString())
            .build();
    }

    private DbRepoStorage resolver(final javax.json.JsonValue storage) {
        final JsonObject repo = Json.createObjectBuilder()
            .add(
                "repo",
                Json.createObjectBuilder().add("type", "pypi").add("storage", storage)
            ).build();
        final List<JsonObject> aliases = List.of(
            Json.createObjectBuilder()
                .add("name", "default")
                .add("config", this.fsConfig())
                .build()
        );
        return new DbRepoStorage(
            new StubRepos(Map.of(REPO, repo)), name -> aliases, new TestStoragesCache()
        );
    }

    /**
     * Read-only repository settings backed by a map.
     */
    private static final class StubRepos implements CrudRepoSettings {

        /**
         * Configs by name.
         */
        private final Map<String, JsonObject> repos;

        StubRepos(final Map<String, JsonObject> repos) {
            this.repos = repos;
        }

        @Override
        public Collection<String> listAll() {
            return this.repos.keySet();
        }

        @Override
        public Collection<String> list(final String uname) {
            return this.repos.keySet();
        }

        @Override
        public boolean exists(final RepositoryName rname) {
            return this.repos.containsKey(rname.toString());
        }

        @Override
        public JsonStructure value(final RepositoryName name) {
            return this.repos.get(name.toString());
        }

        @Override
        public void save(final RepositoryName rname, final JsonStructure value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(final RepositoryName rname) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void move(final RepositoryName rname, final RepositoryName newrname) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasSettingsDuplicates(final RepositoryName rname) {
            return false;
        }
    }
}
