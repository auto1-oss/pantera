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
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.auth.JwtTokens;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.MainSlice;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.settings.StorageByAlias;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.settings.repo.Repositories;
import com.auto1.pantera.test.TestSettings;
import com.auto1.pantera.test.TestStoragesCache;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Client requests through the real {@link RepositorySlices} wiring on the
 * main port (repository name in the path, anonymous-access gate in front):
 * conda and conan must trim the repository name, conda's URL token and
 * NuGet's API key must count as credentials.
 *
 * @since 2.2.9
 */
final class FormatClientWiringTest {

    /**
     * Temp dir.
     */
    @TempDir
    Path tmp;

    /**
     * Tokens.
     */
    private JwtTokens tokens;

    /**
     * Valid access token.
     */
    private String jwt;

    @BeforeEach
    void init() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair keys = gen.generateKeyPair();
        this.tokens = new JwtTokens(
            (RSAPrivateKey) keys.getPrivate(), (RSAPublicKey) keys.getPublic(),
            null, null, null
        );
        this.jwt = this.tokens.generate(new AuthUser("alice", "test"));
    }

    @Test
    void condaPackageDownloadsOnMainPortWithUrlToken() throws Exception {
        new FileStorage(this.tmp).save(
            new Key.From("my-conda", "linux-64", "pkg-1.0-0.tar.bz2"),
            new Content.From("package bytes".getBytes(StandardCharsets.UTF_8))
        ).join();
        final Response rsp = this.slices(
            "my-conda", this.repo("conda").add("url", "http://localhost/my-conda")
        ).slice(new Key.From("my-conda"), 8080).response(
            new RequestLine(
                RqMethod.GET,
                String.format("/my-conda/t/%s/linux-64/pkg-1.0-0.tar.bz2", this.jwt)
            ),
            Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "status", rsp.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "body", rsp.body().asString(), new IsEqual<>("package bytes")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"/my-conda", "/my-conda/"})
    void condaBaseUrlHeadAnswersWithoutCredentials(final String path) throws Exception {
        // anaconda-client's check_server HEADs the base URL before
        // `anaconda login`, i.e. without credentials, even on a private repo.
        MatcherAssert.assertThat(
            this.slices(
                "my-conda", this.repo("conda").add("url", "http://localhost/my-conda")
            ).slice(new Key.From("my-conda"), 8080).response(
                new RequestLine(RqMethod.HEAD, path), Headers.EMPTY, Content.EMPTY
            ).get(30, TimeUnit.SECONDS).status(),
            new IsEqual<>(RsStatus.OK)
        );
    }

    @Test
    void condaPrivateRepoStillRefusesAnonymousReads() throws Exception {
        final RepositorySlices slices = this.slices(
            "my-conda", this.repo("conda").add("url", "http://localhost/my-conda")
        );
        MatcherAssert.assertThat(
            "anonymous HEAD of a file",
            slices.slice(new Key.From("my-conda"), 8080).response(
                new RequestLine(RqMethod.HEAD, "/my-conda/noarch/repodata.json"),
                Headers.EMPTY, Content.EMPTY
            ).get(30, TimeUnit.SECONDS).status(),
            new IsEqual<>(RsStatus.UNAUTHORIZED)
        );
        MatcherAssert.assertThat(
            "anonymous GET of the base URL",
            slices.slice(new Key.From("my-conda"), 8080).response(
                new RequestLine(RqMethod.GET, "/my-conda"), Headers.EMPTY, Content.EMPTY
            ).get(30, TimeUnit.SECONDS).status(),
            new IsEqual<>(RsStatus.UNAUTHORIZED)
        );
    }

    @Test
    void condaCliTokenInFrontOfThePathServesPackage() throws Exception {
        // The conda CLI turns the channel <base>/t/<token> into requests for
        // /t/<token>/<base path>/...; it keeps only [A-Za-z0-9-] of the
        // token, so the JWT is given to it hex-encoded.
        new FileStorage(this.tmp).save(
            new Key.From("my-conda", "linux-64", "pkg-1.0-0.tar.bz2"),
            new Content.From("package bytes".getBytes(StandardCharsets.UTF_8))
        ).join();
        final Response rsp = new MainSlice(
            new TestSettings(),
            this.slices(
                "my-conda", this.repo("conda").add("url", "http://localhost/my-conda")
            )
        ).response(
            new RequestLine(
                RqMethod.GET,
                String.format(
                    "/t/%s/api/my-conda/linux-64/pkg-1.0-0.tar.bz2",
                    java.util.HexFormat.of().formatHex(
                        this.jwt.getBytes(StandardCharsets.US_ASCII)
                    )
                )
            ),
            Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat("status", rsp.status(), new IsEqual<>(RsStatus.OK));
        MatcherAssert.assertThat(
            "body", rsp.body().asString(), new IsEqual<>("package bytes")
        );
    }

    @Test
    void conanRoutesOnMainPort() throws Exception {
        MatcherAssert.assertThat(
            this.slices("my-conan", this.repo("conan")).slice(new Key.From("my-conan"), 8080)
                .response(
                    new RequestLine(RqMethod.GET, "/my-conan/v1/ping"),
                    Headers.from(new Authorization.Bearer(this.jwt)), Content.EMPTY
                ).get(30, TimeUnit.SECONDS).status(),
            new IsEqual<>(RsStatus.ACCEPTED)
        );
    }

    @Test
    void nugetPushAcceptsApiKeyHeader() throws Exception {
        MatcherAssert.assertThat(
            this.nugetPush(this.jwt).status(),
            new IsNot<>(new IsEqual<>(RsStatus.UNAUTHORIZED))
        );
    }

    @Test
    void nugetPushRejectsForgedApiKey() throws Exception {
        MatcherAssert.assertThat(
            this.nugetPush("forged").status(),
            new IsEqual<>(RsStatus.UNAUTHORIZED)
        );
    }

    private Response nugetPush(final String key) throws Exception {
        return this.slices(
            "my-nuget", this.repo("nuget").add("url", "http://localhost/my-nuget")
        ).slice(new Key.From("my-nuget"), 8080).response(
            new RequestLine(RqMethod.PUT, "/my-nuget/package"),
            Headers.from(new Header("X-NuGet-ApiKey", key)),
            Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
    }

    private RepositorySlices slices(final String name, final YamlMappingBuilder repo) {
        return new RepositorySlices(
            new TestSettings(),
            new SingleRepo(
                RepoConfig.from(
                    Yaml.createYamlMappingBuilder().add("repo", repo.build()).build(),
                    new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
                    new Key.From(name),
                    new TestStoragesCache(),
                    false
                )
            ),
            this.tokens
        );
    }

    private YamlMappingBuilder repo(final String type) {
        return Yaml.createYamlMappingBuilder()
            .add("type", type)
            .add(
                "storage",
                Yaml.createYamlMappingBuilder()
                    .add("type", "fs")
                    .add("path", this.tmp.toString())
                    .build()
            );
    }

    /**
     * Repositories holding exactly one config.
     */
    private static final class SingleRepo implements Repositories {

        private final RepoConfig cfg;

        SingleRepo(final RepoConfig cfg) {
            this.cfg = cfg;
        }

        @Override
        public Optional<RepoConfig> config(final String name) {
            return this.cfg.name().equals(name) ? Optional.of(this.cfg) : Optional.empty();
        }

        @Override
        public Collection<RepoConfig> configs() {
            return List.of(this.cfg);
        }
    }
}
