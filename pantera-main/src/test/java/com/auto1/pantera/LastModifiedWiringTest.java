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
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.settings.StorageByAlias;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.settings.repo.Repositories;
import com.auto1.pantera.test.TestSettings;
import com.auto1.pantera.test.TestStoragesCache;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Artifact downloads through the real {@link RepositorySlices} wiring carry
 * a {@code Last-Modified} header for every repository format: generic HTTP
 * clients (a CI tool installer, {@code curl -z}, {@code wget -N}) fail or
 * re-download without it. The value is the time the artifact was stored.
 *
 * @since 2.2.12
 */
final class LastModifiedWiringTest {

    /**
     * Time the artifact was stored.
     */
    private static final Instant STORED = Instant.parse("2018-06-17T18:35:49Z");

    /**
     * {@link #STORED} as an HTTP date.
     */
    private static final String STORED_DATE = "Sun, 17 Jun 2018 18:35:49 GMT";

    /**
     * Repository name.
     */
    private static final String REPO = "my-repo";

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

    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource({
        "maven,  GET,  org/example/tool/1.0/tool-1.0-bin.zip",
        "maven,  HEAD, org/example/tool/1.0/tool-1.0-bin.zip",
        "maven,  GET,  org/example/tool/1.0/tool-1.0-bin.tar.gz",
        "maven,  GET,  org/example/tool/1.0/tool-1.0.jar",
        "gradle, GET,  org/example/tool/1.0/tool-1.0.jar",
        // A binary without a file extension.
        "file,   GET,  tools/linux-amd64/kubectl",
        "file,   HEAD, tools/linux-amd64/kubectl",
        "go,     GET,  example.com/hello/@v/v1.0.1.zip",
        "pypi,   GET,  hello/0.2.0/hello-0.2.0.tar.gz",
        "helm,   GET,  cert-manager/cert-manager-v1.17.4.tgz",
        "npm,    GET,  @acme/pkg/-/@acme/pkg-1.0.0.tgz",
        "php,    GET,  artifacts/acme/lib/2.0.0/acme-lib-2.0.0.zip"
    })
    void storedArtifactIsDatedWithItsStorageTime(
        final String type, final String method, final String artifact
    ) throws Exception {
        new FileStorage(this.tmp).save(
            new Key.From(LastModifiedWiringTest.REPO, artifact),
            new Content.From("artifact bytes".getBytes(StandardCharsets.UTF_8))
        ).get(30, TimeUnit.SECONDS);
        Files.setLastModifiedTime(
            this.tmp.resolve(LastModifiedWiringTest.REPO).resolve(artifact),
            FileTime.from(LastModifiedWiringTest.STORED)
        );
        final Response rsp = this.slices(
            this.repo(type).add("url", "http://localhost/" + LastModifiedWiringTest.REPO)
        ).slice(new Key.From(LastModifiedWiringTest.REPO), 8080).response(
            new RequestLine(
                RqMethod.valueOf(method),
                String.format("/%s/%s", LastModifiedWiringTest.REPO, artifact)
            ),
            Headers.from(new Authorization.Bearer(this.jwt)), Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the artifact is served", rsp.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the response is dated with the time the artifact was stored",
            rsp.headers().values("Last-Modified"),
            new IsEqual<>(List.of(LastModifiedWiringTest.STORED_DATE))
        );
    }

    private RepositorySlices slices(final YamlMappingBuilder repo) {
        return new RepositorySlices(
            new TestSettings(),
            new SingleRepo(
                RepoConfig.from(
                    Yaml.createYamlMappingBuilder().add("repo", repo.build()).build(),
                    new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
                    new Key.From(LastModifiedWiringTest.REPO),
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

        /**
         * The config.
         */
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
