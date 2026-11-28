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
package com.auto1.pantera.docker.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.http.DockerSlice;
import com.auto1.pantera.docker.proxy.ProxyDocker;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.scheduling.ArtifactEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The read-only view of {@link CacheDocker} answers a request that must
 * leave the repository as it found it (a Troubleshoot replay): it reads the
 * cache and the origin as the caching view does and hands on what the
 * origin has, but copies nothing into the cache, queues no artifact event
 * and records no release date. The caching view of the same repository
 * keeps storing.
 *
 * @since 2.2.15
 */
final class CacheDockerReadOnlyTest {

    /**
     * Image name.
     */
    private static final String IMAGE = "library/busybox";

    /**
     * Tag of the image.
     */
    private static final String TAG = "1.36";

    /**
     * Image config.
     */
    private static final byte[] CONFIG =
        "{\"architecture\":\"amd64\",\"created\":\"2020-02-03T04:05:06Z\"}"
            .getBytes(StandardCharsets.UTF_8);

    /**
     * Image layer.
     */
    private static final byte[] LAYER = "layer bytes".getBytes(StandardCharsets.UTF_8);

    /**
     * Digest of the image config.
     */
    private static final String CONFIG_DIGEST =
        new Digest.Sha256(CacheDockerReadOnlyTest.CONFIG).string();

    /**
     * Digest of the image layer.
     */
    private static final String LAYER_DIGEST =
        new Digest.Sha256(CacheDockerReadOnlyTest.LAYER).string();

    /**
     * Media type of the manifest.
     */
    private static final String MEDIA = "application/vnd.docker.distribution.manifest.v2+json";

    /**
     * Image manifest.
     */
    private static final byte[] MANIFEST = String.join(
        "",
        "{\"schemaVersion\":2,\"mediaType\":\"", CacheDockerReadOnlyTest.MEDIA, "\",",
        "\"config\":{\"mediaType\":\"application/vnd.docker.container.image.v1+json\",",
        "\"size\":", String.valueOf(CacheDockerReadOnlyTest.CONFIG.length), ",",
        "\"digest\":\"", CacheDockerReadOnlyTest.CONFIG_DIGEST, "\"},",
        "\"layers\":[{\"mediaType\":\"application/vnd.docker.image.rootfs.diff.tar.gzip\",",
        "\"size\":", String.valueOf(CacheDockerReadOnlyTest.LAYER.length), ",",
        "\"digest\":\"", CacheDockerReadOnlyTest.LAYER_DIGEST, "\"}]}"
    ).getBytes(StandardCharsets.UTF_8);

    /**
     * Digest of the image manifest.
     */
    private static final String MANIFEST_DIGEST =
        new Digest.Sha256(CacheDockerReadOnlyTest.MANIFEST).string();

    /**
     * Cache storage of the proxy.
     */
    private Storage storage;

    /**
     * Events queued for the search index.
     */
    private Queue<ArtifactEvent> events;

    /**
     * Release dates handed to the persisting callback.
     */
    private List<String> persisted;

    /**
     * The upstream registry.
     */
    private Upstream upstream;

    /**
     * Cooldown inspector of the repository.
     */
    private DockerProxyCooldownInspector inspector;

    /**
     * The caching proxy.
     */
    private CacheDocker docker;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.events = new ConcurrentLinkedQueue<>();
        this.persisted = new CopyOnWriteArrayList<>();
        this.upstream = new Upstream();
        this.inspector = new DockerProxyCooldownInspector();
        this.inspector.setReleaseDateCallback(
            (artifact, version, release) -> this.persisted.add(artifact + ':' + version)
        );
        this.docker = new CacheDocker(
            new ProxyDocker("registry", this.upstream),
            new AstoDocker("registry", this.storage),
            Optional.of(this.events),
            Optional.of(this.inspector),
            "https://upstream.example"
        );
    }

    @ParameterizedTest
    @CsvSource({
        "GET,tag", "HEAD,tag", "GET,digest", "HEAD,digest", "GET,blob", "HEAD,blob"
    })
    @Timeout(120)
    void readOnlyViewAnswersWhatTheOriginHasAndStoresNothing(
        final RqMethod method, final String what
    ) throws Exception {
        final String path = CacheDockerReadOnlyTest.path(what);
        final Response res = CacheDockerReadOnlyTest.ask(this.docker.readOnly(), method, path);
        final byte[] body = res.body().asBytesFuture().get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the read-only view says the origin has it",
            res.status(), new IsEqual<>(RsStatus.OK)
        );
        if (method == RqMethod.GET) {
            MatcherAssert.assertThat(
                "and hands on what the origin returned",
                new String(body, StandardCharsets.UTF_8),
                new IsEqual<>(
                    new String(CacheDockerReadOnlyTest.content(what), StandardCharsets.UTF_8)
                )
            );
        }
        // The origin is asked for a manifest with GET whatever the client
        // sent, and for a blob with HEAD before its content is read.
        MatcherAssert.assertThat(
            "the origin was asked as for a client",
            this.upstream.asked("blob".equals(what) ? "HEAD" : "GET", path),
            new IsEqual<>(1)
        );
        // A write would come from a callback after the answer: give one
        // that should not exist the time to show itself.
        for (int idx = 0; idx < 20; idx += 1) {
            MatcherAssert.assertThat(
                "the read-only view writes nothing into the cache storage",
                this.storage.list(Key.ROOT).join().stream().map(Key::string).toList(),
                new IsEqual<>(List.of())
            );
            TimeUnit.MILLISECONDS.sleep(15L);
        }
        MatcherAssert.assertThat(
            "queues no event for the search index",
            this.events.size(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "persists no release date",
            this.persisted, new IsEqual<>(List.of())
        );
        MatcherAssert.assertThat(
            "and leaves the repository's inspector without one",
            this.inspector.releaseDate(
                CacheDockerReadOnlyTest.IMAGE, CacheDockerReadOnlyTest.TAG
            ).join(),
            new IsEqual<>(Optional.<Instant>empty())
        );
    }

    @Test
    @Timeout(120)
    void cachingViewStoresTheTagAfterTheReadOnlyOne() throws Exception {
        final String path = CacheDockerReadOnlyTest.path("tag");
        final ManifestReference ref = ManifestReference.from(CacheDockerReadOnlyTest.TAG);
        CacheDockerReadOnlyTest.ask(this.docker.readOnly(), RqMethod.GET, path)
            .body().asBytesFuture().get(60, TimeUnit.SECONDS);
        final byte[] body = CacheDockerReadOnlyTest.ask(this.docker, RqMethod.GET, path)
            .body().asBytesFuture().get(60, TimeUnit.SECONDS);
        CacheDockerReadOnlyTest.eventually(
            () -> this.cached().manifests().get(ref).join().isPresent()
                && !this.events.isEmpty()
        );
        MatcherAssert.assertThat(
            "a client's request is answered from the origin",
            new String(body, StandardCharsets.UTF_8),
            new IsEqual<>(new String(CacheDockerReadOnlyTest.MANIFEST, StandardCharsets.UTF_8))
        );
        MatcherAssert.assertThat(
            "and its manifest is stored under the tag as before",
            this.cached().manifests().get(ref).join().isPresent(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "with the event of the tag for the search index",
            this.events.stream().map(ArtifactEvent::artifactVersion).toList(),
            new IsEqual<>(List.of(CacheDockerReadOnlyTest.TAG))
        );
        MatcherAssert.assertThat(
            "and its release date",
            this.persisted.contains(
                CacheDockerReadOnlyTest.IMAGE + ':' + CacheDockerReadOnlyTest.TAG
            ),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the origin was asked once for each view",
            this.upstream.asked("GET", path), new IsEqual<>(2)
        );
    }

    @Test
    @Timeout(120)
    void cachingViewStoresTheDigestAfterTheReadOnlyOne() throws Exception {
        final String path = CacheDockerReadOnlyTest.path("digest");
        final ManifestReference ref = ManifestReference.from(
            new Digest.FromString(CacheDockerReadOnlyTest.MANIFEST_DIGEST)
        );
        CacheDockerReadOnlyTest.ask(this.docker.readOnly(), RqMethod.GET, path)
            .body().asBytesFuture().get(60, TimeUnit.SECONDS);
        CacheDockerReadOnlyTest.ask(this.docker, RqMethod.GET, path)
            .body().asBytesFuture().get(60, TimeUnit.SECONDS);
        CacheDockerReadOnlyTest.eventually(
            () -> this.cached().manifests().get(ref).join().isPresent()
        );
        MatcherAssert.assertThat(
            "a client's request stores the manifest under its digest as before",
            this.cached().manifests().get(ref).join().isPresent(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the origin was asked once for each view",
            this.upstream.asked("GET", path), new IsEqual<>(2)
        );
    }

    @Test
    @Timeout(120)
    void cachingViewStoresTheBlobAfterTheReadOnlyOne() throws Exception {
        final String path = CacheDockerReadOnlyTest.path("blob");
        final Digest digest = new Digest.FromString(CacheDockerReadOnlyTest.LAYER_DIGEST);
        CacheDockerReadOnlyTest.ask(this.docker.readOnly(), RqMethod.GET, path)
            .body().asBytesFuture().get(60, TimeUnit.SECONDS);
        final byte[] body = CacheDockerReadOnlyTest.ask(this.docker, RqMethod.GET, path)
            .body().asBytesFuture().get(60, TimeUnit.SECONDS);
        CacheDockerReadOnlyTest.eventually(
            () -> this.cached().layers().get(digest).join().isPresent()
        );
        MatcherAssert.assertThat(
            "a client's request is answered from the origin",
            new String(body, StandardCharsets.UTF_8),
            new IsEqual<>(new String(CacheDockerReadOnlyTest.LAYER, StandardCharsets.UTF_8))
        );
        MatcherAssert.assertThat(
            "and its blob is stored as before",
            this.cached().layers().get(digest).join().isPresent(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the origin was asked once for each view",
            this.upstream.asked("GET", path), new IsEqual<>(2)
        );
    }

    @Test
    @Timeout(120)
    void readOnlyViewServesWhatTheCacheHolds() throws Exception {
        final String manifest = CacheDockerReadOnlyTest.path("tag");
        final String blob = CacheDockerReadOnlyTest.path("blob");
        CacheDockerReadOnlyTest.ask(this.docker, RqMethod.GET, manifest)
            .body().asBytesFuture().get(60, TimeUnit.SECONDS);
        CacheDockerReadOnlyTest.ask(this.docker, RqMethod.GET, blob)
            .body().asBytesFuture().get(60, TimeUnit.SECONDS);
        CacheDockerReadOnlyTest.eventually(
            () -> this.cached().manifests()
                .get(ManifestReference.from(CacheDockerReadOnlyTest.TAG)).join().isPresent()
                && this.cached().layers()
                .get(new Digest.FromString(CacheDockerReadOnlyTest.LAYER_DIGEST))
                .join().isPresent()
        );
        this.upstream.down.set(true);
        final int before = this.upstream.requests.size();
        final Response layer = CacheDockerReadOnlyTest.ask(
            this.docker.readOnly(), RqMethod.GET, blob
        );
        MatcherAssert.assertThat(
            "a blob the proxy holds is served from the cache",
            new String(
                layer.body().asBytesFuture().get(60, TimeUnit.SECONDS), StandardCharsets.UTF_8
            ),
            new IsEqual<>(new String(CacheDockerReadOnlyTest.LAYER, StandardCharsets.UTF_8))
        );
        MatcherAssert.assertThat(
            "without asking the origin, as for a client",
            this.upstream.requests.size(), new IsEqual<>(before)
        );
        final Response tag = CacheDockerReadOnlyTest.ask(
            this.docker.readOnly(), RqMethod.GET, manifest
        );
        MatcherAssert.assertThat(
            "the cached manifest stands in for an origin that cannot answer",
            new String(
                tag.body().asBytesFuture().get(60, TimeUnit.SECONDS), StandardCharsets.UTF_8
            ),
            new IsEqual<>(new String(CacheDockerReadOnlyTest.MANIFEST, StandardCharsets.UTF_8))
        );
        MatcherAssert.assertThat(
            "after the origin was asked first, as for a client",
            this.upstream.asked("GET", manifest), new IsEqual<>(2)
        );
    }

    @Test
    @Timeout(120)
    void readOnlyViewDoesNotReadAFailedOriginAsAbsent() throws Exception {
        this.upstream.down.set(true);
        for (final String what : List.of("tag", "blob")) {
            final Response res = CacheDockerReadOnlyTest.ask(
                this.docker.readOnly(), RqMethod.GET, CacheDockerReadOnlyTest.path(what)
            );
            res.body().asBytesFuture().get(60, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                String.format("a failed origin is not a missing %s", what),
                res.status().serverError(), new IsEqual<>(true)
            );
        }
    }

    /**
     * The image in the proxy's cache storage.
     *
     * @return Repo over the storage, never asking the origin
     */
    private Repo cached() {
        return new AstoDocker("registry", this.storage).repo(CacheDockerReadOnlyTest.IMAGE);
    }

    /**
     * Ask a view of the repository over HTTP.
     *
     * @param view Docker to ask
     * @param method Request method
     * @param path Path below the image name
     * @return Response
     * @throws Exception On error
     */
    private static Response ask(final Docker view, final RqMethod method, final String path)
        throws Exception {
        return new DockerSlice(view).response(
            new RequestLine(
                method, String.format("/v2/%s%s", CacheDockerReadOnlyTest.IMAGE, path)
            ),
            Headers.EMPTY, Content.EMPTY
        ).get(60, TimeUnit.SECONDS);
    }

    /**
     * Wait for a state that follows the answer: a cache write is committed
     * after the body was streamed. The state is polled, never timed.
     *
     * @param state Awaited state
     * @throws InterruptedException If interrupted
     */
    private static void eventually(final BooleanSupplier state) throws InterruptedException {
        for (int idx = 0; idx < 400 && !state.getAsBoolean(); idx += 1) {
            TimeUnit.MILLISECONDS.sleep(25L);
        }
    }

    private static String path(final String what) {
        final String path;
        if ("tag".equals(what)) {
            path = "/manifests/" + CacheDockerReadOnlyTest.TAG;
        } else if ("digest".equals(what)) {
            path = "/manifests/" + CacheDockerReadOnlyTest.MANIFEST_DIGEST;
        } else {
            path = "/blobs/" + CacheDockerReadOnlyTest.LAYER_DIGEST;
        }
        return path;
    }

    private static byte[] content(final String what) {
        final byte[] content;
        if ("blob".equals(what)) {
            content = CacheDockerReadOnlyTest.LAYER;
        } else {
            content = CacheDockerReadOnlyTest.MANIFEST;
        }
        return content;
    }

    /**
     * Upstream registry holding one image: its manifest under every tag and
     * digest, its config and its layer. It can be taken down.
     *
     * @since 2.2.15
     */
    private static final class Upstream implements Slice {

        /**
         * Requests got: method and path.
         */
        private final List<String> requests = new CopyOnWriteArrayList<>();

        /**
         * Whether the registry answers 503.
         */
        private final AtomicBoolean down = new AtomicBoolean();

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final String path = line.uri().getPath();
            this.requests.add(String.format("%s %s", line.method().value(), path));
            return body.asBytesFuture().thenApply(
                ignored -> {
                    final Response res;
                    if (this.down.get()) {
                        res = ResponseBuilder.unavailable().build();
                    } else if (path.contains("/manifests/")) {
                        res = ResponseBuilder.ok()
                            .header("Content-Type", CacheDockerReadOnlyTest.MEDIA)
                            .header(
                                "Docker-Content-Digest", CacheDockerReadOnlyTest.MANIFEST_DIGEST
                            )
                            .body(CacheDockerReadOnlyTest.MANIFEST)
                            .build();
                    } else if (path.endsWith("/blobs/" + CacheDockerReadOnlyTest.CONFIG_DIGEST)) {
                        res = Upstream.blob(line, CacheDockerReadOnlyTest.CONFIG);
                    } else if (path.endsWith("/blobs/" + CacheDockerReadOnlyTest.LAYER_DIGEST)) {
                        res = Upstream.blob(line, CacheDockerReadOnlyTest.LAYER);
                    } else {
                        res = ResponseBuilder.notFound().build();
                    }
                    return res;
                }
            );
        }

        /**
         * How often the registry was asked.
         *
         * @param method Request method
         * @param path Path below the image name
         * @return Number of requests
         */
        int asked(final String method, final String path) {
            final String line = String.format(
                "%s /v2/%s%s", method, CacheDockerReadOnlyTest.IMAGE, path
            );
            return (int) this.requests.stream().filter(line::equals).count();
        }

        private static Response blob(final RequestLine line, final byte[] bytes) {
            final Response res;
            if (line.method() == RqMethod.HEAD) {
                res = ResponseBuilder.ok()
                    .header("Content-Length", String.valueOf(bytes.length))
                    .build();
            } else {
                res = ResponseBuilder.ok().body(bytes).build();
            }
            return res;
        }
    }
}
