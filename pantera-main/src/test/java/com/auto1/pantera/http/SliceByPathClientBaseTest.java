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
import com.auto1.pantera.http.headers.ClientBaseUrlSettings;
import com.auto1.pantera.http.headers.ClientBaseUrlSettingsRegistry;
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
import org.hamcrest.core.IsNot;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
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

    @AfterEach
    void tearDown() {
        ClientBaseUrlSettingsRegistry.uninstall();
    }

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
    void clientSuppliedBaseHeaderIsOverwrittenNotTrusted() {
        // A client that sends the internal header directly (exact case) must
        // never have it echoed back — SliceByPath always re-derives the base
        // for the repository actually addressed instead of trusting inbound
        // input. This is what makes group-wins safe: the header is set
        // exactly once, here, regardless of what arrived on the wire.
        MatcherAssert.assertThat(
            this.observedBaseWithClientSuppliedHeader(
                ClientBaseUrl.HEADER, "https://evil.example.com"
            ),
            new IsEqual<>(Optional.of("http://reg.example.com/test_prefix/npm_group"))
        );
    }

    @Test
    void clientSuppliedBaseHeaderIsOverwrittenEvenInLowercase() {
        // Headers.add(header, true)'s overwrite path compares names
        // case-sensitively, so a lowercase client header would otherwise
        // survive alongside the real one and win, since ClientBaseUrl#first
        // reads index 0. Confirms the strip is genuinely case-insensitive.
        MatcherAssert.assertThat(
            this.observedBaseWithClientSuppliedHeader(
                "x-pantera-client-base", "https://evil.example.com"
            ),
            new IsEqual<>(Optional.of("http://reg.example.com/test_prefix/npm_group"))
        );
    }

    @Test
    void clientSuppliedBaseIsDroppedWhenNoReplacementCanBeDerived() {
        // When SliceByPath cannot derive a value for the resolved repo (the
        // recorded original path does not end with the repo-relative
        // remainder), the header must simply be absent — never fall back to
        // whatever the client supplied.
        final Headers headers = new Headers()
            .add("Host", "reg.example.com")
            .add(ClientBaseUrl.ORIGINAL_PATH, "/does/not/match/the/remainder")
            .add(ClientBaseUrl.HEADER, "https://evil.example.com");
        MatcherAssert.assertThat(
            this.invoke("/test_prefix/npm_group/pnpm", headers),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void clientSuppliedOriginalPathCannotSteerTheDerivedBase() {
        // Full production pipeline: ApiRoutingSlice always sits in front of
        // SliceByPath (MainSlice) and must discard any inbound
        // X-Original-Path before setting its own — otherwise a client could
        // point the derived base's path at a repository of its choosing.
        final PrefixesConfig prefixes = new PrefixesConfig(List.of("test_prefix"));
        final RecordingSlices slices = new RecordingSlices(SliceByPathClientBaseTest.repositories());
        final Slice pipeline = new ApiRoutingSlice(
            new SliceByPath(slices, prefixes), slices.repositories()
        );
        final Headers headers = new Headers()
            .add("Host", "reg.example.com")
            .add(ClientBaseUrl.ORIGINAL_PATH, "/test_prefix/api/npm/evil_group/pnpm");
        pipeline.response(
            new RequestLine(RqMethod.GET, "/test_prefix/api/npm/npm_group/pnpm"),
            headers, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            new ClientBaseUrl(slices.lastHeaders()).stamped(),
            new IsEqual<>(Optional.of("http://reg.example.com/test_prefix/api/npm/npm_group"))
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
     * The security scenario a host allowlist exists to close: {@code curl -H
     * 'Host: evil.tld' <repo>/pnpm} against a repository with no configured
     * {@code url:} (like {@code npm_group} here). Without an allowlist this
     * derives {@code http://evil.tld/...} and {@code SliceByPath} stamps it
     * verbatim — cached, and pointed at a host the attacker chose. With a
     * non-matching allowlist configured, the disallowed {@code Host} must
     * never appear in the derived base.
     */
    @Test
    void hostNotOnTheConfiguredAllowlistIsNeverStampedIntoTheBase() {
        ClientBaseUrlSettingsRegistry.install(
            () -> new ClientBaseUrlSettings(false, List.of("reg.example.com"))
        );
        final Optional<String> stamped = this.observedBase(
            "/test_prefix/npm_group/pnpm", Optional.empty(), "evil.tld"
        );
        MatcherAssert.assertThat(
            "the attacker-chosen Host must never be reflected into the stamped base",
            stamped.orElse(""), new IsNot<>(new StringContains("evil.tld"))
        );
    }

    @Test
    void hostOnTheConfiguredAllowlistIsStampedNormally() {
        ClientBaseUrlSettingsRegistry.install(
            () -> new ClientBaseUrlSettings(false, List.of("reg.example.com"))
        );
        MatcherAssert.assertThat(
            this.observedBase("/test_prefix/npm_group/pnpm", Optional.empty(), "reg.example.com"),
            new IsEqual<>(Optional.of("http://reg.example.com/test_prefix/npm_group"))
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
        return this.observedBase(path, originalPath, "reg.example.com");
    }

    /**
     * Same as {@link #observedBase(String, Optional)} with an explicit
     * {@code Host} value, for allowlist scenarios where the default
     * {@code reg.example.com} would not exercise the interesting case.
     *
     * @param path Request path
     * @param originalPath Pre-rewrite client path, if any
     * @param host {@code Host} header value to send
     * @return Stamped base, if present
     */
    private Optional<String> observedBase(
        final String path, final Optional<String> originalPath, final String host
    ) {
        final Headers headers = new Headers().add("Host", host);
        originalPath.ifPresent(value -> headers.add(ClientBaseUrl.ORIGINAL_PATH, value));
        return this.invoke(path, headers);
    }

    /**
     * Drive {@link SliceByPath} with {@link ClientBaseUrl#HEADER} already
     * present under the given name — simulating a client that sent the
     * internal header itself, in whatever case — returning the base the
     * downstream slice observed.
     *
     * @param headerName Header name to send the value under, any case
     * @param clientSuppliedValue Value the "client" sends for that header
     * @return Stamped base, if present
     */
    private Optional<String> observedBaseWithClientSuppliedHeader(
        final String headerName, final String clientSuppliedValue
    ) {
        final Headers headers = new Headers()
            .add("Host", "reg.example.com")
            .add(headerName, clientSuppliedValue);
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
