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
import com.auto1.pantera.RepositorySlices;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.auth.JwtTokens;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.ClientBaseUrlSettingsRegistry;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.PrefixesConfig;
import com.auto1.pantera.settings.StorageByAlias;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.settings.repo.Repositories;
import com.auto1.pantera.test.TestSettings;
import com.auto1.pantera.test.TestStoragesCache;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.json.Json;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringStartsWith;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Conan on the main port, through the production front of the pipeline
 * ({@link ApiRoutingSlice} and {@link SliceByPath} with a global prefix):
 * the download and upload URLs Pantera hands out must carry the prefix and
 * the repository segment, and requesting them must work.
 *
 * @since 2.2.9
 */
final class ConanMainPortUrlsTest {

    /**
     * Client-facing repository base.
     */
    private static final String BASE = "http://reg.example.com/test_prefix/api/my-conan";

    /**
     * Recipe path.
     */
    private static final String RECIPE = "zlib/1.2.13/_/_";

    /**
     * Temp dir.
     */
    @TempDir
    Path tmp;

    /**
     * Pipeline under test.
     */
    private Slice pipeline;

    /**
     * Valid access token.
     */
    private String jwt;

    @BeforeEach
    void init() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair keys = gen.generateKeyPair();
        final JwtTokens tokens = new JwtTokens(
            (RSAPrivateKey) keys.getPrivate(), (RSAPublicKey) keys.getPublic(),
            null, null, null
        );
        this.jwt = tokens.generate(new AuthUser("alice", "test"));
        final Repositories repos = new Repos(
            this.conan("my-conan"), this.conan("other-conan"), this.conan("shared-conan")
        );
        this.pipeline = new ApiRoutingSlice(
            new SliceByPath(
                new RepositorySlices(
                    new TestSettings(ConanMainPortUrlsTest.policy()), repos, tokens
                ),
                new PrefixesConfig(List.of("test_prefix"))
            ),
            repos
        );
    }

    @AfterEach
    void tearDown() {
        ClientBaseUrlSettingsRegistry.uninstall();
    }

    @Test
    void downloadUrlPointsIntoTheRepositoryAndServesTheFile() throws Exception {
        new FileStorage(this.tmp).save(
            new Key.From("my-conan", ConanMainPortUrlsTest.RECIPE, "0/export/conanfile.py"),
            new Content.From("recipe".getBytes(StandardCharsets.UTF_8))
        ).join();
        final Response urls = this.send(
            RqMethod.GET,
            String.format(
                "/test_prefix/api/my-conan/v1/conans/%s/download_urls",
                ConanMainPortUrlsTest.RECIPE
            ),
            Content.EMPTY
        );
        final String url = Json.createReader(new StringReader(urls.body().asString()))
            .readObject().getString("conanfile.py");
        MatcherAssert.assertThat(
            "download url carries prefix and repository",
            url,
            new IsEqual<>(
                String.format(
                    "%s/%s/0/export/conanfile.py",
                    ConanMainPortUrlsTest.BASE, ConanMainPortUrlsTest.RECIPE
                )
            )
        );
        final Response file = this.send(RqMethod.GET, URI.create(url).getRawPath(), Content.EMPTY);
        MatcherAssert.assertThat(
            "download url serves the file", file.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "file content", file.body().asString(), new IsEqual<>("recipe")
        );
    }

    @Test
    void uploadUrlPointsIntoTheRepositoryAndAcceptsTheFile() throws Exception {
        final Response urls = this.send(
            RqMethod.POST,
            String.format(
                "/test_prefix/api/my-conan/v1/conans/%s/upload_urls",
                ConanMainPortUrlsTest.RECIPE
            ),
            new Content.From("{\"conanfile.py\": 6}".getBytes(StandardCharsets.UTF_8))
        );
        final String url = Json.createReader(new StringReader(urls.body().asString()))
            .readObject().getString("conanfile.py");
        MatcherAssert.assertThat(
            "upload url carries prefix and repository",
            url,
            new StringStartsWith(
                String.format(
                    "%s/%s/0/export/conanfile.py?signature=",
                    ConanMainPortUrlsTest.BASE, ConanMainPortUrlsTest.RECIPE
                )
            )
        );
        final URI uri = URI.create(url);
        final Response put = this.send(
            RqMethod.PUT,
            String.format("%s?%s", uri.getRawPath(), uri.getRawQuery()),
            new Content.From("recipe".getBytes(StandardCharsets.UTF_8))
        );
        MatcherAssert.assertThat(
            "upload url accepts the file", put.status(), new IsEqual<>(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "file stored under the repository",
            new FileStorage(this.tmp).value(
                new Key.From("my-conan", ConanMainPortUrlsTest.RECIPE, "0/export/conanfile.py")
            ).join().asString(),
            new IsEqual<>("recipe")
        );
    }

    @Test
    void uploadUrlCannotWriteIntoRepositoryWithoutWrite() throws Exception {
        final URI uri = this.uploadUrl();
        final Response put = this.send(
            RqMethod.PUT,
            String.format(
                "%s?%s",
                uri.getRawPath().replace("/my-conan/", "/other-conan/"), uri.getRawQuery()
            ),
            new Content.From("evil".getBytes(StandardCharsets.UTF_8))
        );
        MatcherAssert.assertThat(
            "refused in the repository without WRITE",
            put.status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "nothing written",
            new FileStorage(this.tmp).exists(
                new Key.From("other-conan", ConanMainPortUrlsTest.RECIPE, "0/export/conanfile.py")
            ).join(),
            new IsEqual<>(false)
        );
    }

    @Test
    void uploadUrlOfOneRepositoryIsRefusedByAnother() throws Exception {
        final URI uri = this.uploadUrl();
        final Response put = this.send(
            RqMethod.PUT,
            String.format(
                "%s?%s",
                uri.getRawPath().replace("/my-conan/", "/shared-conan/"), uri.getRawQuery()
            ),
            new Content.From("evil".getBytes(StandardCharsets.UTF_8))
        );
        MatcherAssert.assertThat(
            "signature of another repository refused even with WRITE",
            put.status(), new IsEqual<>(RsStatus.UNAUTHORIZED)
        );
        MatcherAssert.assertThat(
            "nothing written",
            new FileStorage(this.tmp).exists(
                new Key.From("shared-conan", ConanMainPortUrlsTest.RECIPE, "0/export/conanfile.py")
            ).join(),
            new IsEqual<>(false)
        );
    }

    @Test
    void conanClientUploadsThroughSignedUrlWithoutCredentials() throws Exception {
        // Conan 1.x sends no Authorization header to a URL carrying
        // ?signature=, so the PUT must pass the anonymous-access gate and be
        // authorised by the signature (issued to alice, who holds WRITE).
        final URI uri = this.uploadUrl();
        final Response put = this.pipeline.response(
            new RequestLine(
                RqMethod.PUT, String.format("%s?%s", uri.getRawPath(), uri.getRawQuery())
            ),
            Headers.from("Host", "reg.example.com"),
            new Content.From("recipe".getBytes(StandardCharsets.UTF_8))
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "credential-less signed PUT accepted", put.status(), new IsEqual<>(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "file stored under the repository",
            new FileStorage(this.tmp).value(
                new Key.From("my-conan", ConanMainPortUrlsTest.RECIPE, "0/export/conanfile.py")
            ).join().asString(),
            new IsEqual<>("recipe")
        );
    }

    @Test
    void credentialLessPutWithoutSignatureIsRefused() throws Exception {
        final Response put = this.pipeline.response(
            new RequestLine(
                RqMethod.PUT,
                String.format(
                    "/test_prefix/api/my-conan/%s/0/export/conanfile.py",
                    ConanMainPortUrlsTest.RECIPE
                )
            ),
            Headers.from("Host", "reg.example.com"),
            new Content.From("evil".getBytes(StandardCharsets.UTF_8))
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "refused", put.status(), new IsEqual<>(RsStatus.UNAUTHORIZED)
        );
        MatcherAssert.assertThat(
            "nothing written",
            new FileStorage(this.tmp).exists(
                new Key.From("my-conan", ConanMainPortUrlsTest.RECIPE, "0/export/conanfile.py")
            ).join(),
            new IsEqual<>(false)
        );
    }

    private URI uploadUrl() throws Exception {
        final Response urls = this.send(
            RqMethod.POST,
            String.format(
                "/test_prefix/api/my-conan/v1/conans/%s/upload_urls",
                ConanMainPortUrlsTest.RECIPE
            ),
            new Content.From("{\"conanfile.py\": 4}".getBytes(StandardCharsets.UTF_8))
        );
        return URI.create(
            Json.createReader(new StringReader(urls.body().asString()))
                .readObject().getString("conanfile.py")
        );
    }

    private RepoConfig conan(final String name) {
        return RepoConfig.from(
            Yaml.createYamlMappingBuilder().add(
                "repo",
                Yaml.createYamlMappingBuilder()
                    .add("type", "conan")
                    .add(
                        "storage",
                        Yaml.createYamlMappingBuilder()
                            .add("type", "fs")
                            .add("path", this.tmp.toString())
                            .build()
                    ).build()
            ).build(),
            new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
            new Key.From(name),
            new TestStoragesCache(),
            false
        );
    }

    /**
     * Alice may read and write my-conan and shared-conan, and only read
     * other-conan.
     * @return Policy
     */
    private static Policy<PermissionCollection> policy() {
        return user -> {
            final Permissions perms = new Permissions();
            perms.add(new AdapterBasicPermission("my-conan", "read,write"));
            perms.add(new AdapterBasicPermission("shared-conan", "read,write"));
            perms.add(new AdapterBasicPermission("other-conan", Action.Standard.READ));
            return perms;
        };
    }

    private Response send(final RqMethod method, final String path, final Content body)
        throws Exception {
        return this.pipeline.response(
            new RequestLine(method, path),
            Headers.from(new Authorization.Bearer(this.jwt))
                .copy().add("Host", "reg.example.com"),
            body
        ).get(30, TimeUnit.SECONDS);
    }

    /**
     * Fixed set of repository configs.
     */
    private static final class Repos implements Repositories {

        private final List<RepoConfig> cfgs;

        Repos(final RepoConfig... cfgs) {
            this.cfgs = List.of(cfgs);
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
}
