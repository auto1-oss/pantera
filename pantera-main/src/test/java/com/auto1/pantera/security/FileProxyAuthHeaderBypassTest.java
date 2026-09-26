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
package com.auto1.pantera.security;

import com.amihaiemil.eoyaml.Yaml;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exploit-regression test for the {@code Authorization}-header-presence
 * bypass on the {@code file-proxy} adapter, exercised through the REAL
 * {@link RepositorySlices} wiring.
 *
 * <p>Before 2.2.9 the file-proxy slice was composed as
 * {@code TimeoutSlice(FileProxy)} with no credential-validating wrapper —
 * the only proxy type lacking {@code CombinedAuthzSliceWrap}. The outer
 * {@link AnonymousAccessSlice} gate only checks that SOME
 * {@code Authorization} header exists and defers validation downstream, so
 * a request carrying {@code Authorization: Bearer garbage} sailed straight
 * into the upstream fetch of a deny-by-default private mirror.</p>
 *
 * <p>The remote points at a discard port so the vulnerable chain fails on
 * the upstream connection (a non-401 status) — what matters is whether the
 * bogus credential is REJECTED with 401 before any upstream work.</p>
 *
 * @since 2.2.9
 */
final class FileProxyAuthHeaderBypassTest {

    @Test
    void bogusBearerIsRejectedOnPrivateFileProxy(@TempDir final Path tmp) throws Exception {
        final RepositorySlices slices = new RepositorySlices(
            new TestSettings(),
            new SingleRepo(fileProxy(tmp)),
            new RejectingTokens()
        );
        final Response response = slices.slice(new Key.From("bin-proxy"), 8080)
            .response(
                new RequestLine(RqMethod.GET, "/bin-proxy/some/file.bin"),
                Headers.from(new Authorization.Bearer("garbage")),
                Content.EMPTY
            ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "a bogus bearer token on a private file-proxy must be rejected with 401, "
                + "not forwarded to the upstream (header presence is not authentication)",
            response.status().code(), new IsEqual<>(401)
        );
    }

    /**
     * A deny-by-default {@code file-proxy} whose remote is a local discard
     * port — any request that reaches the proxy fails on the connection,
     * never with 401.
     */
    private static RepoConfig fileProxy(final Path tmp) {
        return RepoConfig.from(
            Yaml.createYamlMappingBuilder().add(
                "repo", Yaml.createYamlMappingBuilder()
                    .add("type", "file-proxy")
                    .add(
                        "storage",
                        Yaml.createYamlMappingBuilder()
                            .add("type", "fs")
                            .add("path", tmp.toString())
                            .build()
                    )
                    .add(
                        "remotes",
                        Yaml.createYamlSequenceBuilder()
                            .add(
                                Yaml.createYamlMappingBuilder()
                                    .add("url", "http://127.0.0.1:9")
                                    .build()
                            )
                            .build()
                    )
                    .build()
            ).build(),
            new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
            new Key.From("bin-proxy"),
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
     * Token authentication that recognises NO token — every bearer is bogus.
     */
    private static final class RejectingTokens implements Tokens {

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
