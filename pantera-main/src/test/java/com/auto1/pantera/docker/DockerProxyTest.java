/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.adapters.docker.DockerProxy;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.cache.StoragesCache;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.jetty.JettyClientSlices;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.StorageByAlias;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.test.TestStoragesCache;
import org.hamcrest.CustomMatcher;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Tests for {@link DockerProxy}.
 */
class DockerProxyTest {

    private StoragesCache cache;

    @BeforeEach
    void setUp() {
        this.cache = new TestStoragesCache();
    }

    @ParameterizedTest
    @MethodSource("goodConfigs")
    void shouldBuildFromConfig(final String yaml) throws Exception {
        final Slice slice = dockerProxy(this.cache, yaml);
        MatcherAssert.assertThat(
            slice.response(
                new RequestLine(RqMethod.GET, "/"), Headers.EMPTY, Content.EMPTY
            ).join(),
            new RsHasStatus(
                new IsNot<>(
                    new CustomMatcher<>("is server error") {
                        @Override
                        public boolean matches(final Object item) {
                            return ((RsStatus) item).serverError();
                        }
                    }
                )
            )
        );
    }

    @ParameterizedTest
    @MethodSource("badConfigs")
    void shouldFailBuildFromBadConfig(final String yaml) {
        Assertions.assertThrows(
            RuntimeException.class,
            () -> dockerProxy(this.cache, yaml).response(
                new RequestLine(RqMethod.GET, "/"), Headers.EMPTY, Content.EMPTY
            ).join()
        );
    }

    /**
     * B35: a proxy repository is read-only for clients. Blob uploads and
     * manifest pushes used to land in the proxy's cache storage and shadow
     * the upstream tag for every puller (cache poisoning).
     *
     * @param tmp Storage root
     * @throws Exception On error
     */
    @Test
    void rejectsPushesWith405Unsupported(@TempDir final Path tmp) throws Exception {
        final Slice slice = dockerProxy(
            this.cache,
            String.join(
                "\n",
                "repo:",
                "  type: docker-proxy",
                "  remotes:",
                "    - url: registry-1.docker.io",
                "  storage:",
                "    type: fs",
                "    path: " + tmp.toString()
            ),
            new Key.From("my-proxy"),
            (user, pass) -> Optional.of(new AuthUser(user, "test"))
        );
        final Headers basic = Headers.from(
            "Authorization",
            "Basic " + Base64.getEncoder().encodeToString(
                "alice:secret".getBytes(StandardCharsets.UTF_8)
            )
        );
        final Response upload = slice.response(
            new RequestLine(RqMethod.POST, "/my-proxy/library/busybox/blobs/uploads/"),
            basic, Content.EMPTY
        ).join();
        final Response manifest = slice.response(
            new RequestLine(RqMethod.PUT, "/my-proxy/library/busybox/manifests/1.36"),
            basic, new Content.From("{}".getBytes(StandardCharsets.UTF_8))
        ).join();
        MatcherAssert.assertThat(
            "blob upload start must be refused",
            upload.status(), new IsEqual<>(RsStatus.METHOD_NOT_ALLOWED)
        );
        MatcherAssert.assertThat(
            "upload refusal must carry the OCI UNSUPPORTED code",
            upload.body().asString(), new StringContains("UNSUPPORTED")
        );
        MatcherAssert.assertThat(
            "manifest push must be refused",
            manifest.status(), new IsEqual<>(RsStatus.METHOD_NOT_ALLOWED)
        );
    }

    private static DockerProxy dockerProxy(
        StoragesCache cache, String yaml
    ) throws IOException {
        return dockerProxy(cache, yaml, Key.ROOT, (username, password) -> Optional.empty());
    }

    private static DockerProxy dockerProxy(
        StoragesCache cache, String yaml, Key name, Authentication auth
    ) throws IOException {
        return new DockerProxy(
            new JettyClientSlices(),
            RepoConfig.from(
                Yaml.createYamlInput(yaml).readYamlMapping(),
                new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
                name, cache, false
            ),
            Policy.FREE,
            auth,
            token -> java.util.concurrent.CompletableFuture.completedFuture(Optional.empty()),
            Optional.empty(),
            com.auto1.pantera.cooldown.impl.NoopCooldownService.INSTANCE
        );
    }

    private static Stream<String> goodConfigs() {
        return Stream.of(
            "repo:\n  type: docker-proxy\n  remotes:\n    - url: registry-1.docker.io",
            String.join(
                "\n",
                "repo:",
                "  type: docker-proxy",
                "  remotes:",
                "    - url: registry-1.docker.io",
                "      username: admin",
                "      password: qwerty",
                "      priority: 1500",
                "      cache:",
                "        storage:",
                "          type: fs",
                "          path: /var/pantera/data/cache",
                "    - url: another-registry.org:54321",
                "    - url: mcr.microsoft.com",
                "      cache:",
                "        storage: ",
                "          type: fs",
                "          path: /var/pantera/data/local/cache",
                "  storage:",
                "    type: fs",
                "    path: /var/pantera/data/local"
            )
        );
    }

    private static Stream<String> badConfigs() {
        return Stream.of(
            "",
            "repo:",
            "repo:\n  remotes:\n    - attr: value",
            "repo:\n  remotes:\n    - url: registry-1.docker.io\n      username: admin",
            "repo:\n  remotes:\n    - url: registry-1.docker.io\n      password: qwerty"
        );
    }
}
