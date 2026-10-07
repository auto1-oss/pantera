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
import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlMappingBuilder;
import com.amihaiemil.eoyaml.YamlSequence;
import com.auto1.pantera.api.ssl.KeyStore;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.scheduling.MetadataEventQueues;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.LoggingContext;
import com.auto1.pantera.settings.MetricsContext;
import com.auto1.pantera.settings.PanteraSecurity;
import com.auto1.pantera.settings.PrefixesConfig;
import com.auto1.pantera.settings.Settings;
import com.auto1.pantera.settings.StorageByAlias;
import com.auto1.pantera.settings.cache.PanteraCaches;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.settings.repo.Repositories;
import com.auto1.pantera.test.RecordingIndex;
import com.auto1.pantera.test.TestSettings;
import com.auto1.pantera.test.TestStoragesCache;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The native {@code DELETE} of pypi, debian and rpm, as wired by
 * {@link RepositorySlices}, removes the search-index rows of the deleted
 * file: those formats index an upload with its storage key as
 * {@code path_prefix}, which the delete's storage path matches.
 *
 * @since 2.2.10
 */
final class NativeDeleteCascadeRoutingTest {

    /**
     * Bearer token accepted for alice.
     */
    private static final String TOKEN = "alice-token";

    /**
     * Basic password accepted for any user (the debian adapter
     * authenticates with Basic only).
     */
    private static final String SECRET = "secret";

    @Test
    void pypiDeleteRemovesTheIndexRowOfTheFile(@TempDir final Path tmp) throws Exception {
        final RecordingIndex index = new RecordingIndex();
        final RepositorySlices slices = NativeDeleteCascadeRoutingTest.slices(tmp, index);
        final Storage storage = NativeDeleteCascadeRoutingTest.storage(slices, "pypi-local");
        final String sdist = "requests/2.31.0/requests-2.31.0.tar.gz";
        final String wheel = "requests/2.31.0/requests-2.31.0-py3-none-any.whl";
        NativeDeleteCascadeRoutingTest.save(storage, sdist);
        NativeDeleteCascadeRoutingTest.save(storage, wheel);
        index.row("pypi-local", sdist).row("pypi-local", wheel);
        MatcherAssert.assertThat(
            "the native delete answers 200",
            NativeDeleteCascadeRoutingTest.send(
                slices, RqMethod.DELETE, "pypi-local", "/" + sdist
            ).status().code(),
            new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "a delete through the /simple alias also cascades on the storage key",
            NativeDeleteCascadeRoutingTest.send(
                slices, RqMethod.DELETE, "pypi-local", "/simple/" + wheel
            ).status().code(),
            new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "both files' index rows are removed by their storage paths",
            List.of(index.removals(), index.rows().isEmpty()),
            new IsEqual<>(
                List.of(List.of("pypi-local|" + sdist, "pypi-local|" + wheel), true)
            )
        );
    }

    @Test
    void debianDeleteRemovesTheIndexRowOfThePackage(@TempDir final Path tmp)
        throws Exception {
        final RecordingIndex index = new RecordingIndex();
        final RepositorySlices slices = NativeDeleteCascadeRoutingTest.slices(tmp, index);
        final String deb = "main/aglfn_1.7-3_amd64.deb";
        MatcherAssert.assertThat(
            "the upload answers 200",
            slices.slice(new Key.From("deb-local"), 8080).response(
                new RequestLine(RqMethod.PUT, "/deb-local/" + deb),
                Headers.from(new Authorization.Basic("alice", NativeDeleteCascadeRoutingTest.SECRET)),
                new Content.From(new TestResource("debian/aglfn_1.7-3_amd64.deb").asBytes())
            ).get(30, TimeUnit.SECONDS).status().code(),
            new IsEqual<>(200)
        );
        index.row("deb-local", deb);
        MatcherAssert.assertThat(
            "the native delete answers 200",
            NativeDeleteCascadeRoutingTest.send(
                slices, RqMethod.DELETE, "deb-local", "/" + deb
            ).status().code(),
            new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "the package's index row is removed by its storage path",
            List.of(index.removals(), index.rows().isEmpty()),
            new IsEqual<>(List.of(List.of("deb-local|" + deb), true))
        );
    }

    @Test
    void rpmDeleteRemovesTheIndexRowOfThePackage(@TempDir final Path tmp) throws Exception {
        final RecordingIndex index = new RecordingIndex();
        final RepositorySlices slices = NativeDeleteCascadeRoutingTest.slices(tmp, index);
        final Storage storage = NativeDeleteCascadeRoutingTest.storage(slices, "rpm-local");
        final String rpm = "abc-1.01-26.git20200127.fc32.ppc64le.rpm";
        NativeDeleteCascadeRoutingTest.save(storage, rpm);
        index.row("rpm-local", rpm);
        MatcherAssert.assertThat(
            "the native delete is accepted",
            NativeDeleteCascadeRoutingTest.send(
                slices, RqMethod.DELETE, "rpm-local", "/" + rpm + "?force=true&skip_update=true"
            ).status().code(),
            new IsEqual<>(202)
        );
        MatcherAssert.assertThat(
            "the package's index row is removed by its storage path",
            List.of(index.removals(), index.rows().isEmpty()),
            new IsEqual<>(List.of(List.of("rpm-local|" + rpm), true))
        );
    }

    @Test
    void refusedNativeDeleteKeepsTheIndexRow(@TempDir final Path tmp) throws Exception {
        final RecordingIndex index = new RecordingIndex();
        final RepositorySlices slices = NativeDeleteCascadeRoutingTest.slices(tmp, index);
        index.row("pypi-local", "absent/1.0/absent-1.0.tar.gz");
        MatcherAssert.assertThat(
            "a delete of a file that is not stored answers 404",
            NativeDeleteCascadeRoutingTest.send(
                slices, RqMethod.DELETE, "pypi-local", "/absent/1.0/absent-1.0.tar.gz"
            ).status().code(),
            new IsEqual<>(404)
        );
        MatcherAssert.assertThat(
            "the index is not touched",
            index.removals(), new IsEqual<>(List.of())
        );
    }

    /**
     * Request through the slices.
     * @param slices Slices
     * @param method Method
     * @param repo Repository
     * @param path Path inside the repository
     * @return Response
     * @throws Exception On error
     */
    private static Response send(
        final RepositorySlices slices, final RqMethod method, final String repo,
        final String path
    ) throws Exception {
        return slices.slice(new Key.From(repo), 8080).response(
            new RequestLine(method, String.format("/%s%s", repo, path)),
            Headers.from(new Authorization.Basic("alice", NativeDeleteCascadeRoutingTest.SECRET)),
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
     * Save a small file.
     * @param storage Storage
     * @param key Key
     */
    private static void save(final Storage storage, final String key) {
        storage.save(
            new Key.From(key), new Content.From("x".getBytes(StandardCharsets.UTF_8))
        ).join();
    }

    /**
     * Slices over a pypi, deb and rpm repository with a recording index.
     * @param tmp Temp dir
     * @param index Index
     * @return Repository slices
     */
    private static RepositorySlices slices(final Path tmp, final ArtifactIndex index) {
        return new RepositorySlices(
            new Indexed(new TestSettings(), index),
            new Repos(
                List.of(
                    NativeDeleteCascadeRoutingTest.repo(
                        "pypi-local", NativeDeleteCascadeRoutingTest.base("pypi", tmp)
                    ),
                    NativeDeleteCascadeRoutingTest.repo(
                        "deb-local",
                        NativeDeleteCascadeRoutingTest.base("deb", tmp).add(
                            "settings",
                            Yaml.createYamlMappingBuilder()
                                .add("Components", "main")
                                .add("Architectures", "amd64")
                                .build()
                        )
                    ),
                    NativeDeleteCascadeRoutingTest.repo(
                        "rpm-local", NativeDeleteCascadeRoutingTest.base("rpm", tmp)
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
                NativeDeleteCascadeRoutingTest.TOKEN.equals(token)
                    ? Optional.of(new AuthUser("alice", "test"))
                    : Optional.<AuthUser>empty()
            );
        }

        @Override
        public String generate(final AuthUser user) {
            return "token-for-" + user.name();
        }
    }

    /**
     * Test settings with a given search index.
     */
    private static final class Indexed implements Settings {

        /**
         * Origin.
         */
        private final Settings origin;

        /**
         * Index.
         */
        private final ArtifactIndex index;

        Indexed(final Settings origin, final ArtifactIndex index) {
            this.origin = origin;
            this.index = index;
        }

        @Override
        public ArtifactIndex artifactIndex() {
            return this.index;
        }

        @Override
        public Storage configStorage() {
            return this.origin.configStorage();
        }

        @Override
        public PanteraSecurity authz() {
            final PanteraSecurity sec = this.origin.authz();
            return new PanteraSecurity() {
                @Override
                public Authentication authentication() {
                    return (user, pwd) -> NativeDeleteCascadeRoutingTest.SECRET.equals(pwd)
                        ? Optional.of(new AuthUser(user, "test")) : Optional.empty();
                }

                @Override
                public Policy<?> policy() {
                    return sec.policy();
                }

                @Override
                public Optional<Storage> policyStorage() {
                    return sec.policyStorage();
                }
            };
        }

        @Override
        public YamlMapping meta() {
            return this.origin.meta();
        }

        @Override
        public Storage repoConfigsStorage() {
            return this.origin.repoConfigsStorage();
        }

        @Override
        public Optional<KeyStore> keyStore() {
            return this.origin.keyStore();
        }

        @Override
        public MetricsContext metrics() {
            return this.origin.metrics();
        }

        @Override
        public PanteraCaches caches() {
            return this.origin.caches();
        }

        @Override
        public Optional<MetadataEventQueues> artifactMetadata() {
            return this.origin.artifactMetadata();
        }

        @Override
        public Optional<YamlSequence> crontab() {
            return this.origin.crontab();
        }

        @Override
        public LoggingContext logging() {
            return this.origin.logging();
        }

        @Override
        public CooldownSettings cooldown() {
            return this.origin.cooldown();
        }

        @Override
        public Optional<DataSource> artifactsDatabase() {
            return this.origin.artifactsDatabase();
        }

        @Override
        public PrefixesConfig prefixes() {
            return this.origin.prefixes();
        }

        @Override
        public Path configPath() {
            return this.origin.configPath();
        }
    }
}
