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
package com.auto1.pantera.adapters.npm;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMappingBuilder;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.cooldown.metadata.NoopCooldownMetadataService;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.settings.StorageByAlias;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.test.TestStoragesCache;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

/**
 * Regression coverage for the critical finding in WS8 Task 6 review round 1:
 * {@code NpmProxyAdapter} used to eagerly call {@code RepoConfig#url()} (the
 * throwing accessor) even though every downstream consumer of the base URL
 * ({@code NpmProxySlice}, proxy {@code DownloadPackageSlice}) already falls
 * back to the client-facing base stamped by {@code SliceByPath} when none is
 * configured. A stack with {@code npm-proxy}'s {@code url:} key omitted
 * (the corrected sample config, see {@code npm_proxy.yaml}) booted fine but
 * threw {@code IllegalStateException} uncaught on the first request that
 * resolved the repository slice.
 *
 * <p>Local {@code npm} repositories are a different story: {@code
 * NpmrcAuthSlice} and {@code npm.http.DownloadPackageSlice}'s {@code
 * Tarballs} rewriter have no such fallback and hard-require a non-null
 * {@code URL}, so {@code url:} remains genuinely required there -- see
 * {@code com.auto1.pantera.settings.repo.RepoConfigTest#throwsExceptionWhenUrlNotSpecifiedForLocalNpm}
 * for that half of the contract.</p>
 */
final class NpmProxyAdapterUrlOptionalTest {

    private TestStoragesCache cache;

    @BeforeEach
    void setUp() {
        this.cache = new TestStoragesCache();
    }

    @Test
    void constructsWithoutConfiguredUrl() {
        final RepoConfig cfg = this.npmProxyConfig(Optional.empty());
        Assertions.assertDoesNotThrow(
            () -> new NpmProxyAdapter(
                new NoopClientSlices(),
                cfg,
                Optional.empty(),
                NoopCooldownService.INSTANCE,
                NoopCooldownMetadataService.INSTANCE
            )
        );
    }

    @Test
    void constructsWithWellFormedConfiguredUrl() {
        final RepoConfig cfg = this.npmProxyConfig(Optional.of("http://pantera:8080/npm-proxy"));
        Assertions.assertDoesNotThrow(
            () -> new NpmProxyAdapter(
                new NoopClientSlices(),
                cfg,
                Optional.empty(),
                NoopCooldownService.INSTANCE,
                NoopCooldownMetadataService.INSTANCE
            )
        );
    }

    @Test
    void throwsOnMalformedConfiguredUrl() {
        final RepoConfig cfg = this.npmProxyConfig(Optional.of("host:8080/without/scheme"));
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new NpmProxyAdapter(
                new NoopClientSlices(),
                cfg,
                Optional.empty(),
                NoopCooldownService.INSTANCE,
                NoopCooldownMetadataService.INSTANCE
            )
        );
    }

    /**
     * Builds an npm-proxy {@link RepoConfig} with storage and a single
     * remote wired (so the adapter's remote-construction lambda -- which is
     * where {@code asto.orElseThrow} and the base-URL conversion both run --
     * actually executes), and an optional {@code url:} key.
     *
     * @param url Configured {@code url:} value, or empty to omit the key
     * @return Repo config
     */
    private RepoConfig npmProxyConfig(final Optional<String> url) {
        YamlMappingBuilder repo = Yaml.createYamlMappingBuilder()
            .add("type", "npm-proxy")
            .add(
                "remotes",
                Yaml.createYamlSequenceBuilder().add(
                    Yaml.createYamlMappingBuilder()
                        .add("url", "https://registry.npmjs.org")
                        .build()
                ).build()
            )
            .add(
                "storage",
                Yaml.createYamlMappingBuilder()
                    .add("type", "fs")
                    .add("path", "/var/pantera/data")
                    .build()
            );
        if (url.isPresent()) {
            repo = repo.add("url", url.get());
        }
        return RepoConfig.from(
            Yaml.createYamlMappingBuilder().add("repo", repo.build()).build(),
            new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
            new Key.From("npm-proxy-url-optional-test.yml"), this.cache, false
        );
    }

    /**
     * Minimal {@link ClientSlices}: construction never dispatches a real
     * request (client slices in this pipeline are lazy wrappers), so no
     * method here is ever invoked by the tests above.
     */
    private static final class NoopClientSlices implements ClientSlices {

        @Override
        public Slice http(final String host) {
            throw new UnsupportedOperationException("not exercised by construction-only tests");
        }

        @Override
        public Slice http(final String host, final int port) {
            throw new UnsupportedOperationException("not exercised by construction-only tests");
        }

        @Override
        public Slice https(final String host) {
            throw new UnsupportedOperationException("not exercised by construction-only tests");
        }

        @Override
        public Slice https(final String host, final int port) {
            throw new UnsupportedOperationException("not exercised by construction-only tests");
        }
    }
}
