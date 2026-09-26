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
import com.auto1.pantera.docker.perms.DockerActions;
import com.auto1.pantera.docker.perms.DockerRepositoryPermission;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.scheduling.ArtifactEvent;
import io.reactivex.Flowable;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.security.PermissionCollection;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for {@link DockerProxy}.
 */
class DockerProxyTest {

    /**
     * Minimal schema-2 manifest body.
     */
    private static final String MANIFEST = String.join(
        "",
        "{\"schemaVersion\":2,",
        "\"mediaType\":\"application/vnd.docker.distribution.manifest.v2+json\",",
        "\"config\":{\"mediaType\":\"application/vnd.docker.container.image.v1+json\",",
        "\"size\":2,\"digest\":\"sha256:", "c".repeat(64), "\"},\"layers\":[]}"
    );

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

    /**
     * B35/B74: a push-only user (no overwrite) pushing a tag the upstream
     * has must get 405 UNSUPPORTED without the proxy fetching (and caching
     * and publishing) the upstream manifest for the overwrite check.
     *
     * @param tmp Storage root
     */
    @Test
    void refusesManifestPushWithoutUpstreamLookup(@TempDir final Path tmp) {
        final AtomicInteger upstream = new AtomicInteger();
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final Slice slice = DockerProxyTest.pushOnlyProxy(tmp, upstream, events);
        final Response manifest = slice.response(
            new RequestLine(RqMethod.PUT, "/my-proxy/library/busybox/manifests/1.36"),
            DockerProxyTest.alice(),
            new Content.From(DockerProxyTest.MANIFEST.getBytes(StandardCharsets.UTF_8))
        ).join();
        MatcherAssert.assertThat(
            "manifest push must be refused with 405",
            manifest.status(), new IsEqual<>(RsStatus.METHOD_NOT_ALLOWED)
        );
        MatcherAssert.assertThat(
            "refusal must carry the OCI UNSUPPORTED code",
            manifest.body().asString(), new StringContains("UNSUPPORTED")
        );
        MatcherAssert.assertThat(
            "a refused push must not reach the upstream",
            upstream.get(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "a refused push must not queue an artifact event",
            events.size(), new IsEqual<>(0)
        );
    }

    /**
     * B35: PATCH/PUT on an upload session of a proxy answer 405 and drain
     * the request body.
     *
     * @param tmp Storage root
     */
    @Test
    void refusesUploadChunksAndDrainsBody(@TempDir final Path tmp) {
        final Slice slice = DockerProxyTest.pushOnlyProxy(
            tmp, new AtomicInteger(), new ConcurrentLinkedQueue<>()
        );
        final String path = "/my-proxy/library/busybox/blobs/uploads/"
            + UUID.randomUUID();
        for (final RqMethod method : new RqMethod[]{RqMethod.PATCH, RqMethod.PUT}) {
            final AtomicBoolean drained = new AtomicBoolean();
            final Response rsp = slice.response(
                new RequestLine(method, path + "?digest=sha256:" + "a".repeat(64)),
                DockerProxyTest.alice(),
                new Content.From(
                    Flowable.just(ByteBuffer.wrap(new byte[]{1, 2, 3}))
                        .doOnComplete(() -> drained.set(true))
                )
            ).join();
            MatcherAssert.assertThat(
                method + " upload chunk must be refused with 405",
                rsp.status(), new IsEqual<>(RsStatus.METHOD_NOT_ALLOWED)
            );
            MatcherAssert.assertThat(
                method + " refusal must carry the OCI UNSUPPORTED code",
                rsp.body().asString(), new StringContains("UNSUPPORTED")
            );
            MatcherAssert.assertThat(
                method + " request body must be drained",
                drained.get(), new IsEqual<>(true)
            );
        }
    }

    private static Slice pushOnlyProxy(
        final Path tmp, final AtomicInteger upstream, final Queue<ArtifactEvent> events
    ) {
        final Slice remote = (line, headers, body) -> {
            upstream.incrementAndGet();
            return body.discard().thenApply(
                ignored -> ResponseBuilder.ok()
                    .header("Content-Type",
                        "application/vnd.docker.distribution.manifest.v2+json")
                    .header("Docker-Content-Digest", "sha256:" + "b".repeat(64))
                    .body(DockerProxyTest.MANIFEST.getBytes(StandardCharsets.UTF_8))
                    .build()
            );
        };
        final ClientSlices client = new ClientSlices() {
            @Override
            public Slice http(final String host) {
                return remote;
            }

            @Override
            public Slice http(final String host, final int port) {
                return remote;
            }

            @Override
            public Slice https(final String host) {
                return remote;
            }

            @Override
            public Slice https(final String host, final int port) {
                return remote;
            }
        };
        try {
            return new DockerProxy(
                client,
                RepoConfig.from(
                    Yaml.createYamlInput(
                        String.join(
                            "\n",
                            "repo:",
                            "  type: docker-proxy",
                            "  remotes:",
                            "    - url: https://upstream.example",
                            "  storage:",
                            "    type: fs",
                            "    path: " + tmp.toString()
                        )
                    ).readYamlMapping(),
                    new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
                    new Key.From("my-proxy"), new TestStoragesCache(), false
                ),
                user -> {
                    final PermissionCollection perms = new DockerRepositoryPermission(
                        "my-proxy", "*",
                        DockerActions.PULL.mask() | DockerActions.PUSH.mask()
                    ).newPermissionCollection();
                    perms.add(
                        new DockerRepositoryPermission(
                            "my-proxy", "*",
                            DockerActions.PULL.mask() | DockerActions.PUSH.mask()
                        )
                    );
                    return perms;
                },
                (user, pass) -> Optional.of(new AuthUser(user, "test")),
                token -> CompletableFuture.completedFuture(Optional.empty()),
                Optional.of(events),
                com.auto1.pantera.cooldown.impl.NoopCooldownService.INSTANCE
            );
        } catch (final IOException err) {
            throw new UncheckedIOException(err);
        }
    }

    private static Headers alice() {
        return Headers.from(
            "Authorization",
            "Basic " + Base64.getEncoder().encodeToString(
                "alice:secret".getBytes(StandardCharsets.UTF_8)
            )
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
