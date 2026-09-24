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
package com.auto1.pantera.npm;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMappingBuilder;
import com.auto1.pantera.RepositorySlices;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.npm.PerVersionLayout;
import com.auto1.pantera.settings.StorageByAlias;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.settings.repo.Repositories;
import com.auto1.pantera.test.TestSettings;
import com.auto1.pantera.test.TestStoragesCache;
import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.json.Json;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * npm account and registry endpoints through the REAL {@link RepositorySlices}
 * wiring, including the outer anonymous-access gate: {@code npm login}
 * (legacy and web), {@code npm whoami}, {@code npm profile get}, writes to a
 * proxy, and group {@code /-/} endpoints reaching hosted members.
 *
 * @since 2.2.9
 */
final class NpmAccountRoutingTest {

    /**
     * Bearer token the test token service accepts (user "alice").
     */
    private static final String TOKEN = "alice-token";

    /**
     * Legacy login body with wrong credentials, as npm-profile sends it.
     */
    private static final String LOGIN_BODY =
        "{\"_id\":\"org.couchdb.user:nobody\",\"name\":\"nobody\","
            + "\"password\":\"wrong\",\"type\":\"user\",\"roles\":[]}";

    @Test
    void legacyLoginReachesTheLoginHandlerWithoutAuthorization(@TempDir final Path tmp)
        throws Exception {
        for (final String repo : List.of("npm-local", "npm-proxy", "npm-group")) {
            final Response response = NpmAccountRoutingTest.slices(tmp).slice(
                new Key.From(repo), 8080
            ).response(
                new RequestLine(
                    RqMethod.PUT, String.format("/%s/-/user/org.couchdb.user:nobody", repo)
                ),
                Headers.EMPTY,
                new Content.From(NpmAccountRoutingTest.LOGIN_BODY.getBytes(StandardCharsets.UTF_8))
            ).get(30, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                String.format(
                    "%s: the login handler (not the anonymous gate) judged the password", repo
                ),
                response.body().asString(),
                new StringContains("invalid credentials")
            );
        }
    }

    @Test
    void webLoginIsDeclinedSoNpmFallsBackToLegacy(@TempDir final Path tmp) throws Exception {
        for (final String repo : List.of("npm-local", "npm-proxy", "npm-group")) {
            final Response response = NpmAccountRoutingTest.slices(tmp).slice(
                new Key.From(repo), 8080
            ).response(
                new RequestLine(RqMethod.POST, String.format("/%s/-/v1/login", repo)),
                Headers.EMPTY,
                new Content.From("{}".getBytes(StandardCharsets.UTF_8))
            ).get(30, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                String.format("%s: web login answers a non-retried 404", repo),
                response.status().code(), new IsEqual<>(404)
            );
        }
    }

    @Test
    void refusesOversizedAnonymousLoginBodiesWithoutReadingThem(@TempDir final Path tmp)
        throws Exception {
        final int chunks = 1024;
        for (final String repo : List.of("npm-local", "npm-proxy", "npm-group")) {
            for (final RequestLine line : List.of(
                new RequestLine(
                    RqMethod.PUT, String.format("/%s/-/user/org.couchdb.user:nobody", repo)
                ),
                new RequestLine(RqMethod.POST, String.format("/%s/-/v1/login", repo))
            )) {
                final AtomicInteger pulled = new AtomicInteger();
                final Response response = NpmAccountRoutingTest.slices(tmp).slice(
                    new Key.From(repo), 8080
                ).response(
                    line,
                    Headers.EMPTY,
                    new Content.From(
                        Flowable.range(0, chunks).map(
                            idx -> {
                                pulled.incrementAndGet();
                                return ByteBuffer.wrap(new byte[16 * 1024]);
                            }
                        )
                    )
                ).get(30, TimeUnit.SECONDS);
                MatcherAssert.assertThat(
                    String.format("%s %s: an oversized anonymous body is answered 413", repo, line),
                    response.status().code(), new IsEqual<>(413)
                );
                MatcherAssert.assertThat(
                    String.format("%s %s: the body is cancelled at the cap", repo, line),
                    pulled.get() < chunks, new IsEqual<>(true)
                );
            }
        }
    }

    @Test
    void whoamiAndProfileAnswerFromTheIdentityOnProxyAndGroup(@TempDir final Path tmp)
        throws Exception {
        for (final String repo : List.of("npm-proxy", "npm-group")) {
            MatcherAssert.assertThat(
                String.format("%s: npm whoami names the authenticated user", repo),
                NpmAccountRoutingTest.get(tmp, repo, "/-/whoami"),
                new StringContains("\"username\":\"alice\"")
            );
            MatcherAssert.assertThat(
                String.format("%s: npm profile get names the authenticated user", repo),
                NpmAccountRoutingTest.get(tmp, repo, "/-/npm/v1/user"),
                new StringContains("\"name\":\"alice\"")
            );
        }
    }

    @Test
    void logoutIsDeclinedAlikeInEveryMode(@TempDir final Path tmp) throws Exception {
        // R23: npm logout (DELETE /-/user/token/<token>) answered 403 on a
        // group and a proxy but 404 on a local repository.
        for (final String repo : List.of("npm-local", "npm-proxy", "npm-group")) {
            final Response response = NpmAccountRoutingTest.slices(tmp).slice(
                new Key.From(repo), 8080
            ).response(
                new RequestLine(
                    RqMethod.DELETE, String.format("/%s/-/user/token/some-token", repo)
                ),
                Headers.from(new Authorization.Bearer(NpmAccountRoutingTest.TOKEN)),
                Content.EMPTY
            ).get(30, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                String.format("%s: npm logout answers a non-retried 404", repo),
                response.status().code(), new IsEqual<>(404)
            );
            MatcherAssert.assertThat(
                String.format("%s: npm logout is declined as not implemented", repo),
                response.headers().values("X-Pantera-Reason"),
                new IsEqual<>(List.of("not_implemented"))
            );
        }
    }

    @Test
    void publishToAProxyIsMethodNotAllowed(@TempDir final Path tmp) throws Exception {
        MatcherAssert.assertThat(
            NpmAccountRoutingTest.slices(tmp).slice(new Key.From("npm-proxy"), 8080).response(
                new RequestLine(RqMethod.PUT, "/npm-proxy/@qa%2fpkg"),
                Headers.from(new Authorization.Bearer(NpmAccountRoutingTest.TOKEN)),
                new Content.From("{}".getBytes(StandardCharsets.UTF_8))
            ).get(30, TimeUnit.SECONDS).status().code(),
            new IsEqual<>(405)
        );
    }

    @Test
    void proxyWriteRefusalNamesTheAllowedMethods(@TempDir final Path tmp) throws Exception {
        for (final RqMethod method : List.of(RqMethod.PUT, RqMethod.DELETE)) {
            MatcherAssert.assertThat(
                String.format("%s to a proxy lists the allowed methods", method),
                NpmAccountRoutingTest.slices(tmp).slice(new Key.From("npm-proxy"), 8080).response(
                    new RequestLine(method, "/npm-proxy/@qa%2fpkg"),
                    Headers.from(new Authorization.Bearer(NpmAccountRoutingTest.TOKEN)),
                    new Content.From("{}".getBytes(StandardCharsets.UTF_8))
                ).get(30, TimeUnit.SECONDS).headers().values("Allow"),
                new IsEqual<>(List.of("GET, HEAD"))
            );
        }
    }

    @Test
    void groupDistTagsReachTheHostedMember(@TempDir final Path tmp) throws Exception {
        new PerVersionLayout(
            NpmAccountRoutingTest.repo(
                "npm-local", NpmAccountRoutingTest.base("npm", tmp.resolve("local"))
            ).storage()
        ).addVersion(
            new Key.From("@qa-npm/pkg"), "1.0.0",
            Json.createObjectBuilder()
                .add("name", "@qa-npm/pkg")
                .add("version", "1.0.0")
                .build()
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "npm dist-tag ls through the group answers from the hosted member",
            NpmAccountRoutingTest.get(tmp, "npm-group", "/-/package/@qa-npm%2fpkg/dist-tags"),
            new StringContains("\"latest\":\"1.0.0\"")
        );
    }

    /**
     * Authenticated GET, body as text.
     * @param tmp Temp dir
     * @param repo Repository
     * @param path Path inside the repository
     * @return Body
     * @throws Exception On error
     */
    private static String get(final Path tmp, final String repo, final String path)
        throws Exception {
        return NpmAccountRoutingTest.slices(tmp).slice(new Key.From(repo), 8080).response(
            new RequestLine(RqMethod.GET, String.format("/%s%s", repo, path)),
            Headers.from(new Authorization.Bearer(NpmAccountRoutingTest.TOKEN)),
            Content.EMPTY
        ).get(30, TimeUnit.SECONDS).body().asString();
    }

    /**
     * Slices over a local, a proxy (remote on a discard port) and a group.
     * @param tmp Temp dir
     * @return Repository slices
     */
    private static RepositorySlices slices(final Path tmp) {
        return new RepositorySlices(
            new TestSettings(),
            new Repos(
                List.of(
                    NpmAccountRoutingTest.repo(
                        "npm-local", NpmAccountRoutingTest.base("npm", tmp.resolve("local"))
                    ),
                    NpmAccountRoutingTest.repo(
                        "npm-proxy",
                        NpmAccountRoutingTest.base("npm-proxy", tmp.resolve("proxy")).add(
                            "remotes",
                            Yaml.createYamlSequenceBuilder().add(
                                Yaml.createYamlMappingBuilder()
                                    .add("url", "http://127.0.0.1:9").build()
                            ).build()
                        )
                    ),
                    NpmAccountRoutingTest.repo(
                        "npm-group",
                        NpmAccountRoutingTest.base("npm-group", tmp.resolve("group")).add(
                            "members",
                            Yaml.createYamlSequenceBuilder()
                                .add("npm-local").add("npm-proxy").build()
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
                NpmAccountRoutingTest.TOKEN.equals(token)
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
