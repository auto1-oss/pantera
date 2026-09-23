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
package com.auto1.pantera.docker;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.RepositorySlices;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.headers.WwwAuthenticate;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.settings.StorageByAlias;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.settings.repo.Repositories;
import com.auto1.pantera.test.TestSettings;
import com.auto1.pantera.test.TestStoragesCache;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * B81: an anonymous request to a (deny-by-default) docker repository is
 * rejected by the generic anonymous-access gate; through the real
 * {@link RepositorySlices} wiring that 401 must carry the OCI error body and
 * the same {@code Basic, Bearer} challenge as a credentialed failure.
 */
final class DockerOciErrorsWiringTest {

    @Test
    void anonymous401IsAnOciErrorWithTheAdapterChallenge(@TempDir final Path tmp)
        throws Exception {
        final Response response = new RepositorySlices(
            new TestSettings(),
            new SingleRepo(DockerOciErrorsWiringTest.local(tmp)),
            new NoTokens()
        ).slice(new Key.From("docker-local"), 8080).response(
            new RequestLine(RqMethod.GET, "/docker-local/app/tags/list"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "anonymous read is refused",
            response.status().code(), new IsEqual<>(401)
        );
        MatcherAssert.assertThat(
            "challenge matches the credentialed-failure path",
            response.headers().values(WwwAuthenticate.NAME),
            new IsEqual<>(List.of("Basic realm=\"pantera\", Bearer realm=\"pantera\""))
        );
        MatcherAssert.assertThat(
            "body is the OCI UNAUTHORIZED error",
            response.body().asString(), new StringContains("\"UNAUTHORIZED\"")
        );
    }

    private static RepoConfig local(final Path tmp) {
        return RepoConfig.from(
            Yaml.createYamlMappingBuilder().add(
                "repo", Yaml.createYamlMappingBuilder()
                    .add("type", "docker")
                    .add(
                        "storage",
                        Yaml.createYamlMappingBuilder()
                            .add("type", "fs")
                            .add("path", tmp.toString())
                            .build()
                    )
                    .build()
            ).build(),
            new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
            new Key.From("docker-local"),
            new TestStoragesCache(),
            false
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

    /**
     * Token authentication that recognises no token.
     */
    private static final class NoTokens implements Tokens {

        @Override
        public TokenAuthentication auth() {
            return token -> CompletableFuture.completedFuture(Optional.<AuthUser>empty());
        }

        @Override
        public String generate(final AuthUser user) {
            throw new UnsupportedOperationException("not used");
        }
    }
}
