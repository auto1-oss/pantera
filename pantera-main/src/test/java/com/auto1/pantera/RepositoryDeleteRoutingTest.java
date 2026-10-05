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
import com.amihaiemil.eoyaml.YamlMappingBuilder;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.settings.StorageByAlias;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.settings.repo.Repositories;
import com.auto1.pantera.test.TestSettings;
import com.auto1.pantera.test.TestStoragesCache;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code DELETE /<repo>/<path>} as wired by {@link RepositorySlices}: the
 * generic delete for hosted paths, the adapters' native delete endpoints
 * left alone, proxy eviction.
 *
 * @since 2.2.10
 */
final class RepositoryDeleteRoutingTest {

    /**
     * Bearer token accepted for alice.
     */
    private static final String TOKEN = "alice-token";

    /**
     * Body of the generic delete's 404.
     */
    private static final String GENERIC_404 = "Nothing is stored or indexed at path";

    @Test
    void fileRepositoryDeletesThroughTheGenericDelete(@TempDir final Path tmp)
        throws Exception {
        final RepositorySlices slices = RepositoryDeleteRoutingTest.slices(tmp);
        final Storage storage = RepositoryDeleteRoutingTest.storage(slices, "files");
        RepositoryDeleteRoutingTest.save(storage, "dir/a.bin");
        RepositoryDeleteRoutingTest.save(storage, "dir/b.bin");
        MatcherAssert.assertThat(
            "a file delete answers 204",
            RepositoryDeleteRoutingTest.delete(slices, "files", "/dir/a.bin").status().code(),
            new IsEqual<>(204)
        );
        MatcherAssert.assertThat(
            "a folder delete answers 204",
            RepositoryDeleteRoutingTest.delete(slices, "files", "/dir").status().code(),
            new IsEqual<>(204)
        );
        MatcherAssert.assertThat(
            "the folder is gone",
            storage.list(Key.ROOT).join().isEmpty(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "a path with nothing there answers 404",
            RepositoryDeleteRoutingTest.delete(slices, "files", "/dir/a.bin").status().code(),
            new IsEqual<>(404)
        );
    }

    @Test
    void npmTarballIsGenericUnpublishStaysNative(@TempDir final Path tmp) throws Exception {
        final RepositorySlices slices = RepositoryDeleteRoutingTest.slices(tmp);
        final Storage storage = RepositoryDeleteRoutingTest.storage(slices, "npm-local");
        RepositoryDeleteRoutingTest.save(storage, "qa-pkg/-/qa-pkg-1.0.0.tgz");
        MatcherAssert.assertThat(
            "a tarball path is deleted by the generic delete",
            RepositoryDeleteRoutingTest.delete(
                slices, "npm-local", "/qa-pkg/-/qa-pkg-1.0.0.tgz"
            ).status().code(),
            new IsEqual<>(204)
        );
        MatcherAssert.assertThat(
            "the tarball is gone",
            storage.exists(new Key.From("qa-pkg/-/qa-pkg-1.0.0.tgz")).join(),
            new IsEqual<>(false)
        );
        final Response unpublish = RepositoryDeleteRoutingTest.delete(
            slices, "npm-local", "/qa-pkg/-rev/undefined"
        );
        MatcherAssert.assertThat(
            "an unpublish never reaches the generic delete",
            unpublish.body().asString(),
            new IsNot<>(new StringContains(RepositoryDeleteRoutingTest.GENERIC_404))
        );
    }

    @Test
    void helmChartApiStaysNative(@TempDir final Path tmp) throws Exception {
        final RepositorySlices slices = RepositoryDeleteRoutingTest.slices(tmp);
        final Storage storage = RepositoryDeleteRoutingTest.storage(slices, "helm-local");
        storage.save(
            new Key.From("index.yaml"),
            new Content.From(
                String.join(
                    "\n",
                    "apiVersion: v1",
                    "entries:",
                    "  qa:",
                    "  - {name: qa, version: 1.0.0, urls: [qa-1.0.0.tgz]}",
                    ""
                ).getBytes(StandardCharsets.UTF_8)
            )
        ).join();
        final Response chart = RepositoryDeleteRoutingTest.delete(
            slices, "helm-local", "/charts/absent/9.9.9"
        );
        MatcherAssert.assertThat(
            "the chart API delete never reaches the generic delete",
            chart.body().asString(),
            new IsNot<>(new StringContains(RepositoryDeleteRoutingTest.GENERIC_404))
        );
        RepositoryDeleteRoutingTest.save(storage, "other-2.0.0.tgz");
        MatcherAssert.assertThat(
            "a chart archive path is deleted by the generic delete",
            RepositoryDeleteRoutingTest.delete(slices, "helm-local", "/other-2.0.0.tgz")
                .status().code(),
            new IsEqual<>(204)
        );
    }

    @Test
    void fileProxyDeleteEvictsTheCachedCopy(@TempDir final Path tmp) throws Exception {
        final RepositorySlices slices = RepositoryDeleteRoutingTest.slices(tmp);
        final Storage cache = RepositoryDeleteRoutingTest.storage(slices, "files-remote");
        RepositoryDeleteRoutingTest.save(cache, "dir/cached.bin");
        MatcherAssert.assertThat(
            "the eviction answers 204",
            RepositoryDeleteRoutingTest.delete(slices, "files-remote", "/dir/cached.bin")
                .status().code(),
            new IsEqual<>(204)
        );
        MatcherAssert.assertThat(
            "the cached copy is gone",
            cache.exists(new Key.From("dir/cached.bin")).join(), new IsEqual<>(false)
        );
    }

    /**
     * Authenticated DELETE.
     * @param slices Slices
     * @param repo Repository
     * @param path Path inside the repository
     * @return Response
     * @throws Exception On error
     */
    private static Response delete(
        final RepositorySlices slices, final String repo, final String path
    ) throws Exception {
        return slices.slice(new Key.From(repo), 8080).response(
            new RequestLine(RqMethod.DELETE, String.format("/%s%s", repo, path)),
            Headers.from(new Authorization.Bearer(RepositoryDeleteRoutingTest.TOKEN)),
            Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
    }

    /**
     * Storage of a repository.
     * @param slices Slices
     * @param repo Repository
     * @return Storage (repository-relative keys)
     */
    private static Storage storage(final RepositorySlices slices, final String repo) {
        return slices.repositories().config(repo).orElseThrow().storage();
    }

    /**
     * Save a one-byte file.
     * @param storage Storage
     * @param key Key
     */
    private static void save(final Storage storage, final String key) {
        storage.save(
            new Key.From(key), new Content.From("x".getBytes(StandardCharsets.UTF_8))
        ).join();
    }

    /**
     * Slices over a file, npm, helm and file-proxy repository.
     * @param tmp Temp dir
     * @return Repository slices
     */
    private static RepositorySlices slices(final Path tmp) {
        return new RepositorySlices(
            new TestSettings(),
            new Repos(
                List.of(
                    RepositoryDeleteRoutingTest.repo(
                        "files", RepositoryDeleteRoutingTest.base("file", tmp)
                    ),
                    RepositoryDeleteRoutingTest.repo(
                        "npm-local", RepositoryDeleteRoutingTest.base("npm", tmp)
                    ),
                    RepositoryDeleteRoutingTest.repo(
                        "helm-local",
                        RepositoryDeleteRoutingTest.base("helm", tmp)
                            .add("url", "http://localhost:8080/helm-local")
                    ),
                    RepositoryDeleteRoutingTest.repo(
                        "files-remote",
                        RepositoryDeleteRoutingTest.base("file-proxy", tmp).add(
                            "remotes",
                            Yaml.createYamlSequenceBuilder().add(
                                Yaml.createYamlMappingBuilder()
                                    .add("url", "http://127.0.0.1:9").build()
                            ).build()
                        )
                    )
                )
            ),
            new AliceTokens()
        );
    }

    /**
     * Repo YAML with type and fs storage.
     * @param type Repo type
     * @param dir Storage dir
     * @return Builder
     */
    private static YamlMappingBuilder base(final String type, final Path dir) {
        return Yaml.createYamlMappingBuilder()
            .add("type", type)
            .add(
                "storage",
                Yaml.createYamlMappingBuilder()
                    .add("type", "fs")
                    .add("path", dir.toString())
                    .build()
            );
    }

    /**
     * Repo config.
     * @param name Name
     * @param repo Repo YAML
     * @return Config
     */
    private static RepoConfig repo(final String name, final YamlMappingBuilder repo) {
        return RepoConfig.from(
            Yaml.createYamlMappingBuilder().add("repo", repo.build()).build(),
            new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
            new Key.From(name),
            new TestStoragesCache(),
            false
        );
    }

    /**
     * Fixed set of repositories.
     */
    private static final class Repos implements Repositories {

        /**
         * Configs.
         */
        private final List<RepoConfig> cfgs;

        Repos(final List<RepoConfig> cfgs) {
            this.cfgs = cfgs;
        }

        @Override
        public Optional<RepoConfig> config(final String name) {
            return this.cfgs.stream().filter(cfg -> cfg.name().equals(name)).findFirst();
        }

        @Override
        public Collection<RepoConfig> configs() {
            return this.cfgs;
        }
    }

    /**
     * Token service accepting one bearer token for "alice".
     */
    private static final class AliceTokens implements Tokens {

        @Override
        public TokenAuthentication auth() {
            return token -> CompletableFuture.completedFuture(
                RepositoryDeleteRoutingTest.TOKEN.equals(token)
                    ? Optional.of(new AuthUser("alice", "test"))
                    : Optional.<AuthUser>empty()
            );
        }

        @Override
        public String generate(final AuthUser user) {
            return "token-for-" + user.name();
        }
    }
}
