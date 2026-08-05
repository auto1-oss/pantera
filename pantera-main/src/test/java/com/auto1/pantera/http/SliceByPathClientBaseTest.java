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
package com.auto1.pantera.http;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.RepositorySlices;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.headers.ClientBaseUrl;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.settings.PrefixesConfig;
import com.auto1.pantera.settings.StorageByAlias;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.settings.repo.Repositories;
import com.auto1.pantera.test.TestSettings;
import com.auto1.pantera.test.TestStoragesCache;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Tests that {@link SliceByPath} stamps {@link ClientBaseUrl#HEADER} on the
 * headers it forwards to the resolved repository slice.
 */
final class SliceByPathClientBaseTest {

    @Test
    void stampsApiRouteBaseFromOriginalPath() {
        MatcherAssert.assertThat(
            this.observedBase(
                "/test_prefix/npm_group/pnpm",
                Optional.of("/test_prefix/api/npm/npm_group/pnpm")
            ),
            new IsEqual<>(Optional.of("http://reg.example.com/test_prefix/api/npm/npm_group"))
        );
    }

    @Test
    void stampsPlainRouteBaseWhenNoOriginalPath() {
        MatcherAssert.assertThat(
            this.observedBase("/test_prefix/npm_group/pnpm", Optional.empty()),
            new IsEqual<>(Optional.of("http://reg.example.com/test_prefix/npm_group"))
        );
    }

    @Test
    void doesNotOverwriteAnAlreadyStampedBase() {
        // Group-wins: the group's base is stamped before any member slice runs.
        MatcherAssert.assertThat(
            this.observedBaseWithPreStamp("https://h/api/npm/npm_group"),
            new IsEqual<>(Optional.of("https://h/api/npm/npm_group"))
        );
    }

    @Test
    void configuredUrlOverridesDerivedBase() {
        // The addressed repo's own `url:` wins over path-derivation entirely.
        MatcherAssert.assertThat(
            this.observedBase("/npm_proxy/pnpm", Optional.empty()),
            new IsEqual<>(Optional.of("https://upstream.example.com/npm_proxy"))
        );
    }

    /**
     * Drive {@link SliceByPath} with the given request path and an optional
     * pre-rewrite {@code X-Original-Path} header, returning the base the
     * downstream slice observed.
     *
     * @param path Request path
     * @param originalPath Pre-rewrite client path, if any
     * @return Stamped base, if present
     */
    private Optional<String> observedBase(final String path, final Optional<String> originalPath) {
        final Headers headers = new Headers().add("Host", "reg.example.com");
        originalPath.ifPresent(value -> headers.add(ClientBaseUrl.ORIGINAL_PATH, value));
        return this.invoke(path, headers);
    }

    /**
     * Drive {@link SliceByPath} with a base already stamped by an outer
     * slice, returning the base the downstream slice observed.
     *
     * @param preStamped Base stamped before {@link SliceByPath} runs
     * @return Stamped base, if present
     */
    private Optional<String> observedBaseWithPreStamp(final String preStamped) {
        final Headers headers = new Headers()
            .add("Host", "reg.example.com")
            .add(ClientBaseUrl.HEADER, preStamped);
        return this.invoke("/test_prefix/npm_group/pnpm", headers);
    }

    /**
     * Invoke {@link SliceByPath} and read back the base from the headers
     * the recording fake slice observed.
     *
     * @param path Request path
     * @param headers Inbound headers
     * @return Stamped base, if present
     */
    private Optional<String> invoke(final String path, final Headers headers) {
        final PrefixesConfig prefixes = new PrefixesConfig(List.of("test_prefix"));
        final RecordingSlices slices = new RecordingSlices(SliceByPathClientBaseTest.repositories());
        new SliceByPath(slices, prefixes).response(
            new RequestLine(RqMethod.GET, path), headers, Content.EMPTY
        ).join();
        return new ClientBaseUrl(slices.lastHeaders()).stamped();
    }

    /**
     * One group repository (no {@code url:}) and one proxy repository
     * (explicit {@code url:}), addressable by name.
     *
     * @return Repositories stub
     */
    private static Repositories repositories() {
        final RepoConfig group = SliceByPathClientBaseTest.repoConfig("npm_group", "npm-group", null);
        final RepoConfig proxy = SliceByPathClientBaseTest.repoConfig(
            "npm_proxy", "npm-proxy", "https://upstream.example.com/npm_proxy"
        );
        return new Repositories() {
            @Override
            public Optional<RepoConfig> config(final String name) {
                final Optional<RepoConfig> result;
                if ("npm_group".equals(name)) {
                    result = Optional.of(group);
                } else if ("npm_proxy".equals(name)) {
                    result = Optional.of(proxy);
                } else {
                    result = Optional.empty();
                }
                return result;
            }

            @Override
            public Collection<RepoConfig> configs() {
                return Arrays.asList(group, proxy);
            }
        };
    }

    /**
     * Build a minimal {@link RepoConfig} with the given type and optional
     * {@code url:}, no storage node (never resolved by {@link RecordingSlices}).
     *
     * @param name Repository name
     * @param type Repository type
     * @param url Configured URL, or null when none should be set
     * @return Repo configuration
     */
    private static RepoConfig repoConfig(final String name, final String type, final String url) {
        final YamlMapping repoNode;
        if (url == null) {
            repoNode = Yaml.createYamlMappingBuilder().add("type", type).build();
        } else {
            repoNode = Yaml.createYamlMappingBuilder().add("type", type).add("url", url).build();
        }
        return RepoConfig.from(
            Yaml.createYamlMappingBuilder().add("repo", repoNode).build(),
            new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
            new Key.From(name),
            new TestStoragesCache(),
            false
        );
    }

    /**
     * Subclass of {@link RepositorySlices} that bypasses real slice
     * resolution and records the headers the resolved slice observes,
     * while exposing a real {@link Repositories} stub via the inherited
     * {@code repositories()} accessor.
     */
    private static final class RecordingSlices extends RepositorySlices {

        /**
         * Headers observed by the last {@link Slice#response} call.
         */
        private Headers lastHeaders;

        RecordingSlices(final Repositories repos) {
            super(new TestSettings(), repos, null);
        }

        @Override
        public Slice slice(final Key name, final int port) {
            return (line, headers, body) -> {
                this.lastHeaders = headers;
                return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
            };
        }

        Headers lastHeaders() {
            return this.lastHeaders;
        }
    }
}
