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
import com.amihaiemil.eoyaml.YamlSequenceBuilder;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.Headers;
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
 * A group serves through its members' CURRENT configuration: a member
 * that is re-pointed (or a nested group whose member list changes) takes
 * effect in every group that embeds it as soon as the changed repository
 * is invalidated, without the group itself being edited.
 *
 * @since 2.2.9
 */
final class GroupMemberConfigChangeTest {

    /**
     * Bearer token the test token service accepts.
     */
    private static final String TOKEN = "alice-token";

    @Test
    void groupPicksUpAMemberRepointedToOtherStorage(@TempDir final Path tmp) throws Exception {
        final Path one = tmp.resolve("one");
        final Path two = tmp.resolve("two");
        GroupMemberConfigChangeTest.store(GroupMemberConfigChangeTest.file("member", one), "from-one");
        GroupMemberConfigChangeTest.store(GroupMemberConfigChangeTest.file("member", two), "from-two");
        final Repos repos = new Repos();
        repos.put(GroupMemberConfigChangeTest.file("member", one));
        repos.put(GroupMemberConfigChangeTest.group("grp", "member"));
        final RepositorySlices slices = new RepositorySlices(
            new TestSettings(), repos, new AliceTokens()
        );
        MatcherAssert.assertThat(
            "the group serves the member's original storage",
            GroupMemberConfigChangeTest.get(slices, "grp"), new IsEqual<>("from-one")
        );
        repos.put(GroupMemberConfigChangeTest.file("member", two));
        slices.invalidateRepo("member");
        MatcherAssert.assertThat(
            "the group serves the member's new storage",
            GroupMemberConfigChangeTest.get(slices, "grp"), new IsEqual<>("from-two")
        );
    }

    @Test
    void outerGroupPicksUpANestedGroupMemberChange(@TempDir final Path tmp) throws Exception {
        final RepoConfig one = GroupMemberConfigChangeTest.file("one", tmp.resolve("one"));
        final RepoConfig two = GroupMemberConfigChangeTest.file("two", tmp.resolve("two"));
        GroupMemberConfigChangeTest.store(one, "from-one");
        GroupMemberConfigChangeTest.store(two, "from-two");
        final Repos repos = new Repos();
        repos.put(one);
        repos.put(two);
        repos.put(GroupMemberConfigChangeTest.group("inner", "one"));
        repos.put(GroupMemberConfigChangeTest.group("outer", "inner"));
        final RepositorySlices slices = new RepositorySlices(
            new TestSettings(), repos, new AliceTokens()
        );
        MatcherAssert.assertThat(
            "the outer group serves the nested group's original member",
            GroupMemberConfigChangeTest.get(slices, "outer"), new IsEqual<>("from-one")
        );
        repos.put(GroupMemberConfigChangeTest.group("inner", "two"));
        slices.invalidateRepo("inner");
        MatcherAssert.assertThat(
            "the outer group serves the nested group's new member",
            GroupMemberConfigChangeTest.get(slices, "outer"), new IsEqual<>("from-two")
        );
    }

    /**
     * GET x.txt through a repository.
     * @param slices Slices
     * @param repo Repository
     * @return Body
     * @throws Exception On error
     */
    private static String get(final RepositorySlices slices, final String repo)
        throws Exception {
        return slices.slice(new Key.From(repo), 8080).response(
            new RequestLine(RqMethod.GET, String.format("/%s/x.txt", repo)),
            Headers.from(new Authorization.Bearer(GroupMemberConfigChangeTest.TOKEN)),
            Content.EMPTY
        ).get(30, TimeUnit.SECONDS).body().asString();
    }

    /**
     * Save x.txt with the given text into a repository's storage.
     * @param cfg Repository config
     * @param text File text
     */
    private static void store(final RepoConfig cfg, final String text) {
        cfg.storage().save(
            new Key.From("x.txt"), new Content.From(text.getBytes(StandardCharsets.UTF_8))
        ).join();
    }

    /**
     * File repository config over a directory.
     * @param name Name
     * @param dir Storage dir
     * @return Config
     */
    private static RepoConfig file(final String name, final Path dir) {
        return GroupMemberConfigChangeTest.config(
            name,
            Yaml.createYamlMappingBuilder()
                .add("type", "file")
                .add(
                    "storage",
                    Yaml.createYamlMappingBuilder()
                        .add("type", "fs")
                        .add("path", dir.toString())
                        .build()
                )
        );
    }

    /**
     * File group config.
     * @param name Name
     * @param members Members
     * @return Config
     */
    private static RepoConfig group(final String name, final String... members) {
        YamlSequenceBuilder seq = Yaml.createYamlSequenceBuilder();
        for (final String member : members) {
            seq = seq.add(member);
        }
        return GroupMemberConfigChangeTest.config(
            name,
            Yaml.createYamlMappingBuilder()
                .add("type", "file-group")
                .add("members", seq.build())
        );
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
     * Mutable set of repositories.
     */
    private static final class Repos implements Repositories {

        /**
         * Configs by name.
         */
        private final Map<String, RepoConfig> cfgs = new ConcurrentHashMap<>();

        /**
         * Add or replace a config.
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
                GroupMemberConfigChangeTest.TOKEN.equals(token)
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
