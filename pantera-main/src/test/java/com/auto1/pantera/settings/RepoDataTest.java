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
package com.auto1.pantera.settings;

import com.auto1.pantera.api.RepositoryName;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cache.StoragesCache;
import com.auto1.pantera.test.TestStoragesCache;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Test for {@link RepoData}.
 */
class RepoDataTest {

    /**
     * Test repository name.
     */
    private static final String REPO = "my-repo";

    /**
     * Maximum awaiting time duration.
     */
    private static final long MAX_WAIT = Duration.ofMinutes(1).toMillis();

    /**
     * Sleep duration.
     */
    private static final long SLEEP_DURATION = Duration.ofMillis(100).toMillis();

    /**
     * Temp dir.
     */
    @TempDir
    Path temp;

    /**
     * Test settings storage.
     */
    private BlockingStorage stngs;

    /**
     * Test settings storage.
     */
    private Storage storage;

    /**
     * Test data storage.
     */
    private BlockingStorage data;

    /**
     * Storages cache.
     */
    private StoragesCache cache;

    @BeforeEach
    void init() {
        this.cache = new TestStoragesCache();
        this.storage = new InMemoryStorage();
        this.stngs = new BlockingStorage(this.storage);
        this.data = new BlockingStorage(new FileStorage(this.temp));
    }

    @Test
    void removesData() {
        this.stngs.save(
            new Key.From(String.format("%s.yml", RepoDataTest.REPO)),
            this.repoSettings().getBytes(StandardCharsets.UTF_8)
        );
        this.data.save(new Key.From(RepoDataTest.REPO, "first.txt"), new byte[]{});
        this.data.save(new Key.From(RepoDataTest.REPO, "second.txt"), new byte[]{});
        new RepoData(this.storage, this.cache)
            .remove(new RepositoryName.Simple(RepoDataTest.REPO)).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "Repository data are removed",
            this.waitCondition(() -> this.data.list(Key.ROOT).isEmpty())
        );
    }

    @Test
    void movesData() {
        this.stngs.save(
            new Key.From(String.format("%s.yml", RepoDataTest.REPO)),
            this.repoSettings().getBytes(StandardCharsets.UTF_8)
        );
        this.data.save(new Key.From(RepoDataTest.REPO, "first.txt"), new byte[]{});
        this.data.save(new Key.From(RepoDataTest.REPO, "second.txt"), new byte[]{});
        new RepoData(this.storage, this.cache)
            .move(
                new RepositoryName.Simple(RepoDataTest.REPO), new RepositoryName.Simple("new-repo")
            ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "Repository data are moved",
            this.waitCondition(
                () ->
                    this.data.list(Key.ROOT).stream()
                        .map(Key::string).toList()
                        .containsAll(List.of("new-repo/first.txt", "new-repo/second.txt"))
            )
        );
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"_storages.yaml", "my-repo/_storages.yaml"}
    )
    void movesDataWithAlias(final String key) {
        this.stngs.save(
            new Key.From(String.format("%s.yml", RepoDataTest.REPO)),
            this.repoSettingsWithAlias().getBytes(StandardCharsets.UTF_8)
        );
        this.stngs.save(
            new Key.From(key),
            this.storageAlias().getBytes(StandardCharsets.UTF_8)
        );
        this.data.save(new Key.From(RepoDataTest.REPO, "first.txt"), new byte[]{});
        this.data.save(new Key.From(RepoDataTest.REPO, "second.txt"), new byte[]{});
        new RepoData(this.storage, this.cache).move(
            new RepositoryName.Simple(RepoDataTest.REPO), new RepositoryName.Simple("new-repo")
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "Repository data are moved",
            this.waitCondition(
                () ->
                    this.data.list(Key.ROOT).stream()
                        .map(Key::string).toList()
                        .containsAll(List.of("new-repo/first.txt", "new-repo/second.txt"))
            )
        );
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"_storages.yaml", "my-repo/_storages.yaml"}
    )
    void removesDataWithAlias(final String key) {
        this.stngs.save(
            new Key.From(String.format("%s.yml", RepoDataTest.REPO)),
            this.repoSettingsWithAlias().getBytes(StandardCharsets.UTF_8)
        );
        this.stngs.save(
            new Key.From(key), this.storageAlias().getBytes(StandardCharsets.UTF_8)
        );
        this.data.save(new Key.From(RepoDataTest.REPO, "first.txt"), new byte[]{});
        this.data.save(new Key.From(RepoDataTest.REPO, "second.txt"), new byte[]{});
        new RepoData(this.storage, this.cache)
            .remove(new RepositoryName.Simple(RepoDataTest.REPO)).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "Repository data are moved",
            this.waitCondition(() -> this.data.list(Key.ROOT).isEmpty())
        );
    }

    @Test
    void removesDataOfDbOnlyRepositoryAndLeavesSiblingsAlone() {
        // B06: a repository created through the API exists only in the DB
        // (no YAML file); its data used to survive DELETE and come back
        // when the name was reused. The storage is a raw-prefix store (as
        // S3 is), so a sibling "my-repo-2" shares the string prefix.
        final Storage shared = new InMemoryStorage();
        final BlockingStorage blocking = new BlockingStorage(shared);
        blocking.save(new Key.From(RepoDataTest.REPO, "a.txt"), new byte[]{1});
        blocking.save(new Key.From(RepoDataTest.REPO, "sub", "b.txt"), new byte[]{1});
        blocking.save(new Key.From("my-repo-2", "keep.txt"), new byte[]{1});
        new RepoData(this.storage, new FixedStoragesCache(shared))
            .remove(
                new RepositoryName.Simple(RepoDataTest.REPO),
                new SingleRepoSettings(RepoDataTest.REPO, RepoDataTest.inlineStorage())
            ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            blocking.list(Key.ROOT).stream().map(Key::string).toList(),
            new IsEqual<>(List.of("my-repo-2/keep.txt"))
        );
    }

    @Test
    void removesNothingForRepositoryWithoutStorage() {
        final Storage shared = new InMemoryStorage();
        final BlockingStorage blocking = new BlockingStorage(shared);
        blocking.save(new Key.From("grp", "a.txt"), new byte[]{1});
        new RepoData(this.storage, new FixedStoragesCache(shared))
            .remove(
                new RepositoryName.Simple("grp"),
                new SingleRepoSettings(
                    "grp",
                    javax.json.Json.createObjectBuilder().add(
                        "repo", javax.json.Json.createObjectBuilder()
                            .add("type", "maven-group")
                            .add("members", javax.json.Json.createArrayBuilder().add("x"))
                    ).build()
                )
            ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            blocking.list(Key.ROOT).size(),
            new IsEqual<>(1)
        );
    }

    @Test
    void folderDeleteKeepsSiblingSharingTheStringPrefix() {
        final Storage shared = new InMemoryStorage();
        final BlockingStorage blocking = new BlockingStorage(shared);
        blocking.save(new Key.From(RepoDataTest.REPO, "com/acme/lib/1.0/lib-1.0.jar"), new byte[]{1});
        blocking.save(
            new Key.From(RepoDataTest.REPO, "com/acme/lib-extra/1.0/lib-extra-1.0.jar"),
            new byte[]{1}
        );
        final RepoData data = new RepoData(this.storage, new FixedStoragesCache(shared));
        final SingleRepoSettings crs =
            new SingleRepoSettings(RepoDataTest.REPO, RepoDataTest.inlineStorage());
        data.deletePackageFolder(new RepositoryName.Simple(RepoDataTest.REPO), "com/acme/lib", crs)
            .toCompletableFuture().join();
        data.deleteArtifact(new RepositoryName.Simple(RepoDataTest.REPO), "com/acme/li", crs)
            .toCompletableFuture().join();
        MatcherAssert.assertThat(
            blocking.list(Key.ROOT).stream().map(Key::string).toList(),
            new IsEqual<>(List.of("my-repo/com/acme/lib-extra/1.0/lib-extra-1.0.jar"))
        );
    }

    private static javax.json.JsonObject inlineStorage() {
        return javax.json.Json.createObjectBuilder().add(
            "repo", javax.json.Json.createObjectBuilder()
                .add("type", "file")
                .add(
                    "storage",
                    javax.json.Json.createObjectBuilder().add("type", "fs").add("path", "/unused")
                )
        ).build();
    }

    /**
     * Storages cache that answers every storage block with one storage.
     */
    private static final class FixedStoragesCache extends StoragesCache {
        /**
         * The storage.
         */
        private final Storage fixed;

        FixedStoragesCache(final Storage fixed) {
            super();
            this.fixed = fixed;
        }

        @Override
        public Storage storage(final com.amihaiemil.eoyaml.YamlMapping yaml) {
            return this.fixed;
        }
    }

    /**
     * DB-style settings holding exactly one repository.
     */
    private static final class SingleRepoSettings
        implements com.auto1.pantera.settings.repo.CrudRepoSettings {
        /**
         * Repository name.
         */
        private final String name;

        /**
         * Its config.
         */
        private final javax.json.JsonObject config;

        SingleRepoSettings(final String name, final javax.json.JsonObject config) {
            this.name = name;
            this.config = config;
        }

        @Override
        public java.util.Collection<String> listAll() {
            return List.of(this.name);
        }

        @Override
        public java.util.Collection<String> list(final String uname) {
            return List.of(this.name);
        }

        @Override
        public boolean exists(final RepositoryName rname) {
            return this.name.equals(rname.toString());
        }

        @Override
        public javax.json.JsonStructure value(final RepositoryName rname) {
            return this.config;
        }

        @Override
        public void save(final RepositoryName rname, final javax.json.JsonStructure value) {
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

    private String repoSettings() {
        return String.join(
            System.lineSeparator(),
            "repo:",
            "  type: binary",
            "  storage:",
            "    type: fs",
            String.format("    path: %s", this.temp.toString())
        );
    }

    private String repoSettingsWithAlias() {
        return String.join(
            System.lineSeparator(),
            "repo:",
            "  type: binary",
            "  storage: local"
        );
    }

    private String storageAlias() {
        return String.join(
            System.lineSeparator(),
            "storages:",
            "  default:",
            "    type: fs",
            "    path: /usr/def",
            "  local:",
            "    type: fs",
            String.format("    path: %s", this.temp.toString())
        );
    }

    /**
     * Awaiting of action during maximum 5 seconds.
     * Allows to wait result of action during period of time.
     * @param action Action
     * @return Result of action
     */
    private Boolean waitCondition(final Supplier<Boolean> action) {
        final long max = System.currentTimeMillis() + RepoDataTest.MAX_WAIT;
        boolean res;
        do {
            res = action.get();
            if (res) {
                break;
            } else {
                try {
                    TimeUnit.MILLISECONDS.sleep(RepoDataTest.SLEEP_DURATION);
                } catch (final InterruptedException exc) {
                    break;
                }
            }
        } while (System.currentTimeMillis() < max);
        if (!res) {
            res = action.get();
        }
        return res;
    }
}
