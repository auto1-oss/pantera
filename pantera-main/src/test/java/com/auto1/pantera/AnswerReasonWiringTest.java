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
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.log.AnswerReason;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every repository, of whatever type, answers a status of 400 or above with
 * the reason on the answer (see {@link AnswerReason}); the access record of
 * the request reports it as {@code event.reason}.
 *
 * @since 2.2.12
 */
final class AnswerReasonWiringTest {

    /**
     * Bearer token the test token service accepts.
     */
    private static final String TOKEN = "alice-token";

    @Test
    void aMissingFileOfAHostedRepositoryIsNotFound(@TempDir final Path tmp) throws Exception {
        final Response answer = AnswerReasonWiringTest.get(
            AnswerReasonWiringTest.slices(tmp), "files", true
        );
        MatcherAssert.assertThat(
            "the file is not there",
            answer.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "and the answer says so",
            new AnswerReason(answer).token(), new IsEqual<>(Optional.of("not_found"))
        );
    }

    @Test
    void aRequestWithoutCredentialsIsAskedToAuthenticate(@TempDir final Path tmp)
        throws Exception {
        final Response answer = AnswerReasonWiringTest.get(
            AnswerReasonWiringTest.slices(tmp), "files", false
        );
        MatcherAssert.assertThat(
            "the request is refused",
            answer.status(), new IsEqual<>(RsStatus.UNAUTHORIZED)
        );
        MatcherAssert.assertThat(
            "and the answer says why",
            new AnswerReason(answer).token(),
            new IsEqual<>(Optional.of("authentication_required"))
        );
    }

    @Test
    void aGroupWhoseMembersHaveNoSuchFileIsNotFound(@TempDir final Path tmp) throws Exception {
        final Response answer = AnswerReasonWiringTest.get(
            AnswerReasonWiringTest.slices(tmp), "grp", true
        );
        MatcherAssert.assertThat(
            "no member has the file",
            answer.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "and the group's answer carries a reason too",
            new AnswerReason(answer).token().isPresent(), new IsEqual<>(true)
        );
    }

    /**
     * GET a file that does not exist.
     * @param slices Slices
     * @param repo Repository
     * @param authenticated Whether to send the token
     * @return Answer of the repository, as the access-log slice receives it
     * @throws Exception On error
     */
    private static Response get(
        final RepositorySlices slices, final String repo, final boolean authenticated
    ) throws Exception {
        return slices.slice(new Key.From(repo), 8080).response(
            new RequestLine(RqMethod.GET, String.format("/%s/missing.txt", repo)),
            authenticated
                ? Headers.from(new Authorization.Bearer(AnswerReasonWiringTest.TOKEN))
                : Headers.EMPTY,
            Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
    }

    /**
     * Slices over a file repository and a group of it.
     * @param tmp Storage dir
     * @return Slices
     */
    private static RepositorySlices slices(final Path tmp) {
        final Repos repos = new Repos();
        repos.put(
            AnswerReasonWiringTest.config(
                "files",
                Yaml.createYamlMappingBuilder().add("type", "file").add(
                    "storage",
                    Yaml.createYamlMappingBuilder()
                        .add("type", "fs").add("path", tmp.toString()).build()
                )
            )
        );
        repos.put(
            AnswerReasonWiringTest.config(
                "grp",
                Yaml.createYamlMappingBuilder().add("type", "file-group").add(
                    "members", Yaml.createYamlSequenceBuilder().add("files").build()
                )
            )
        );
        return new RepositorySlices(new TestSettings(), repos, new AliceTokens());
    }

    /**
     * Repo config.
     * @param name Name
     * @param repo Repo YAML
     * @return Config
     */
    private static RepoConfig config(final String name, final YamlMappingBuilder repo) {
        return RepoConfig.from(
            Yaml.createYamlMappingBuilder().add("repo", repo.build()).build(),
            new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
            new Key.From(name),
            new TestStoragesCache(),
            false
        );
    }

    /**
     * Set of repositories.
     */
    private static final class Repos implements Repositories {

        /**
         * Configs by name.
         */
        private final Map<String, RepoConfig> cfgs = new ConcurrentHashMap<>();

        /**
         * Add a config.
         * @param cfg Config
         */
        void put(final RepoConfig cfg) {
            this.cfgs.put(cfg.name(), cfg);
        }

        @Override
        public Optional<RepoConfig> config(final String name) {
            return Optional.ofNullable(this.cfgs.get(name));
        }

        @Override
        public Collection<RepoConfig> configs() {
            return List.copyOf(this.cfgs.values());
        }
    }

    /**
     * Token service accepting one bearer token for "alice".
     */
    private static final class AliceTokens implements Tokens {

        @Override
        public TokenAuthentication auth() {
            return token -> CompletableFuture.completedFuture(
                AnswerReasonWiringTest.TOKEN.equals(token)
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
