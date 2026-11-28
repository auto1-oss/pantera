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
package com.auto1.pantera.adapters.docker;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cache.StoragesCache;
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.asto.RegistryRoot;
import com.auto1.pantera.docker.cooldown.DockerCooldownResponseFactory;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.StorageByAlias;
import com.auto1.pantera.settings.repo.RepoConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A Docker proxy with cooldown stores a manifest and queues its event for
 * the search index only when cooldown allows the version. A version in
 * cooldown is refused and leaves no manifest and no index row behind: a
 * stored one would show up in search and in a group's index.
 *
 * @since 2.2.15
 */
final class DockerProxyCooldownCacheTest {

    /**
     * Repository name.
     */
    private static final String REPO = "my-proxy";

    /**
     * Image name at the upstream.
     */
    private static final String IMAGE = "library/busybox";

    /**
     * Tag of the image.
     */
    private static final String TAG = "1.36";

    /**
     * Tag of the multi-arch image.
     */
    private static final String MULTI = "multi";

    /**
     * Media type of an image manifest.
     */
    private static final String MEDIA = "application/vnd.docker.distribution.manifest.v2+json";

    /**
     * Media type of an image index.
     */
    private static final String INDEX_MEDIA = "application/vnd.oci.image.index.v1+json";

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
     * Image manifest.
     */
    private static final byte[] MANIFEST = DockerProxyCooldownCacheTest.manifest(
        DockerProxyCooldownCacheTest.LAYER
    );

    /**
     * Digest of the image manifest.
     */
    private static final String MANIFEST_DIGEST =
        new Digest.Sha256(DockerProxyCooldownCacheTest.MANIFEST).string();

    /**
     * Image index of the multi-arch tag: its one platform is the image.
     */
    private static final byte[] INDEX = String.join(
        "",
        "{\"schemaVersion\":2,\"mediaType\":\"", DockerProxyCooldownCacheTest.INDEX_MEDIA, "\",",
        "\"manifests\":[{\"mediaType\":\"", DockerProxyCooldownCacheTest.MEDIA, "\",",
        "\"size\":", String.valueOf(DockerProxyCooldownCacheTest.MANIFEST.length), ",",
        "\"digest\":\"", DockerProxyCooldownCacheTest.MANIFEST_DIGEST, "\",",
        "\"platform\":{\"architecture\":\"amd64\",\"os\":\"linux\"}}]}"
    ).getBytes(StandardCharsets.UTF_8);

    /**
     * Digest of the image index.
     */
    private static final String INDEX_DIGEST =
        new Digest.Sha256(DockerProxyCooldownCacheTest.INDEX).string();

    @BeforeAll
    static void registerTheDockerRefusal() {
        CooldownResponseRegistry.instance().register(
            new DockerCooldownResponseFactory(), "docker-proxy"
        );
    }

    @Test
    @Timeout(120)
    void blockedTagIsNeitherStoredNorIndexed() throws Exception {
        final Fixture fixture = new Fixture(true);
        MatcherAssert.assertThat(
            "a tag in cooldown is refused",
            fixture.get("/manifests/" + DockerProxyCooldownCacheTest.TAG),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
        fixture.leftNoManifest();
    }

    @Test
    @Timeout(120)
    void blockedTagStaysRefusedWhileTheBlockHolds() throws Exception {
        final Fixture fixture = new Fixture(true);
        final String path = "/manifests/" + DockerProxyCooldownCacheTest.TAG;
        fixture.get(path);
        MatcherAssert.assertThat(
            "a second request for the tag is refused as the first",
            fixture.get(path), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "nothing of the first was kept: the upstream is asked again",
            fixture.upstream.asked("GET", path), new IsEqual<>(2)
        );
        fixture.leftNoManifest();
    }

    @Test
    @Timeout(120)
    void blockedDigestIsNotStored() throws Exception {
        final Fixture fixture = new Fixture(true);
        MatcherAssert.assertThat(
            "a digest in cooldown is refused",
            fixture.get("/manifests/" + DockerProxyCooldownCacheTest.MANIFEST_DIGEST),
            new IsEqual<>(RsStatus.FORBIDDEN)
        );
        fixture.leftNoManifest();
    }

    @Test
    @Timeout(120)
    void blockedImageIndexStoresNeitherTheIndexNorItsPlatform() throws Exception {
        final Fixture fixture = new Fixture(true);
        MatcherAssert.assertThat(
            "a multi-arch tag in cooldown is refused",
            fixture.get("/manifests/" + DockerProxyCooldownCacheTest.MULTI),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
        fixture.leftNoManifest();
    }

    @Test
    @Timeout(120)
    void tagOutOfCooldownIsStoredAndIndexed() throws Exception {
        final Fixture fixture = new Fixture(true);
        final String path = "/manifests/" + DockerProxyCooldownCacheTest.TAG;
        fixture.get(path);
        fixture.cooldown.blocks.set(false);
        MatcherAssert.assertThat(
            "the tag is served once its cooldown is over",
            fixture.get(path), new IsEqual<>(RsStatus.OK)
        );
        final ManifestReference ref = ManifestReference.from(DockerProxyCooldownCacheTest.TAG);
        DockerProxyCooldownCacheTest.eventually(
            () -> fixture.cached().manifests().get(ref).join().isPresent()
                && !fixture.events.isEmpty()
        );
        MatcherAssert.assertThat(
            "and stored under its tag",
            fixture.cached().manifests().get(ref).join().isPresent(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "with the event of the tag for the search index",
            fixture.events.stream().map(ArtifactEvent::artifactVersion).toList(),
            new IsEqual<>(List.of(DockerProxyCooldownCacheTest.TAG))
        );
    }

    @Test
    @Timeout(120)
    void allowedImageIndexIsStoredAndIndexed() throws Exception {
        final Fixture fixture = new Fixture(false);
        MatcherAssert.assertThat(
            "an allowed multi-arch tag is served",
            fixture.get("/manifests/" + DockerProxyCooldownCacheTest.MULTI),
            new IsEqual<>(RsStatus.OK)
        );
        final ManifestReference tag = ManifestReference.from(DockerProxyCooldownCacheTest.MULTI);
        DockerProxyCooldownCacheTest.eventually(
            () -> fixture.cached().manifests().get(tag).join().isPresent()
                && !fixture.events.isEmpty()
        );
        MatcherAssert.assertThat(
            "the index is stored under its tag",
            fixture.cached().manifests().get(tag).join().isPresent(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the tag is queued for the search index",
            fixture.events.stream().map(ArtifactEvent::artifactVersion).toList(),
            new IsEqual<>(List.of(DockerProxyCooldownCacheTest.MULTI))
        );
    }

    @Test
    @Timeout(120)
    void headOfBlockedTagAnswersAsBeforeAndStoresNothing() throws Exception {
        final Fixture fixture = new Fixture(true);
        final Response head = fixture.ask(
            RqMethod.HEAD, "/manifests/" + DockerProxyCooldownCacheTest.TAG
        );
        MatcherAssert.assertThat(
            "a HEAD of a tag in cooldown is answered as before",
            head.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "with the digest the tag resolves to",
            DockerProxyCooldownCacheTest.header(head, "Docker-Content-Digest"),
            new IsEqual<>(DockerProxyCooldownCacheTest.MANIFEST_DIGEST)
        );
        MatcherAssert.assertThat(
            "and the media type of the manifest",
            DockerProxyCooldownCacheTest.header(head, "Content-Type"),
            new IsEqual<>(DockerProxyCooldownCacheTest.MEDIA)
        );
        fixture.decided(DockerProxyCooldownCacheTest.MANIFEST_DIGEST);
        MatcherAssert.assertThat(
            "cooldown decided on the tag and on its digest, as for a GET",
            fixture.cooldown.versions,
            new IsEqual<>(
                List.of(DockerProxyCooldownCacheTest.TAG, DockerProxyCooldownCacheTest.MANIFEST_DIGEST)
            )
        );
        fixture.leftNoManifest();
    }

    @Test
    @Timeout(120)
    void headOfAllowedTagIsStoredAndIndexed() throws Exception {
        final Fixture fixture = new Fixture(false);
        final Response head = fixture.ask(
            RqMethod.HEAD, "/manifests/" + DockerProxyCooldownCacheTest.TAG
        );
        MatcherAssert.assertThat(
            "a HEAD of an allowed tag is answered",
            head.status(), new IsEqual<>(RsStatus.OK)
        );
        final ManifestReference ref = ManifestReference.from(DockerProxyCooldownCacheTest.TAG);
        DockerProxyCooldownCacheTest.eventually(
            () -> fixture.cached().manifests().get(ref).join().isPresent()
                && !fixture.events.isEmpty()
        );
        MatcherAssert.assertThat(
            "the tag is stored as before",
            fixture.cached().manifests().get(ref).join().isPresent(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "and queued for the search index",
            fixture.events.stream().map(ArtifactEvent::artifactVersion).toList(),
            new IsEqual<>(List.of(DockerProxyCooldownCacheTest.TAG))
        );
    }

    @Test
    @Timeout(120)
    void headOfBlockedDigestAnswersAsBeforeAndStoresNothing() throws Exception {
        final Fixture fixture = new Fixture(true);
        final Response head = fixture.ask(
            RqMethod.HEAD, "/manifests/" + DockerProxyCooldownCacheTest.MANIFEST_DIGEST
        );
        MatcherAssert.assertThat(
            "a HEAD of a digest in cooldown is answered as before",
            DockerProxyCooldownCacheTest.header(head, "Docker-Content-Digest"),
            new IsEqual<>(DockerProxyCooldownCacheTest.MANIFEST_DIGEST)
        );
        fixture.decided(DockerProxyCooldownCacheTest.MANIFEST_DIGEST);
        MatcherAssert.assertThat(
            "cooldown decided on the digest, as for a GET",
            fixture.cooldown.versions,
            new IsEqual<>(List.of(DockerProxyCooldownCacheTest.MANIFEST_DIGEST))
        );
        fixture.leftNoManifest();
    }

    @Test
    @Timeout(120)
    void headOfBlockedImageIndexAnswersAsBeforeAndStoresNothing() throws Exception {
        final Fixture fixture = new Fixture(true);
        final Response head = fixture.ask(
            RqMethod.HEAD, "/manifests/" + DockerProxyCooldownCacheTest.MULTI
        );
        MatcherAssert.assertThat(
            "a HEAD of a multi-arch tag in cooldown is answered as before",
            DockerProxyCooldownCacheTest.header(head, "Docker-Content-Digest"),
            new IsEqual<>(DockerProxyCooldownCacheTest.INDEX_DIGEST)
        );
        fixture.decided(DockerProxyCooldownCacheTest.INDEX_DIGEST);
        MatcherAssert.assertThat(
            "cooldown decided on the tag and on the digest of the index",
            fixture.cooldown.versions,
            new IsEqual<>(
                List.of(DockerProxyCooldownCacheTest.MULTI, DockerProxyCooldownCacheTest.INDEX_DIGEST)
            )
        );
        fixture.leftNoManifest();
    }

    @Test
    @Timeout(120)
    void containerdPullOfBlockedVersionIsRefusedByDigestAndStoresNothing() throws Exception {
        final Fixture fixture = new Fixture(true);
        final Response head = fixture.ask(
            RqMethod.HEAD, "/manifests/" + DockerProxyCooldownCacheTest.TAG
        );
        MatcherAssert.assertThat(
            "the tag is resolved as before",
            head.status(), new IsEqual<>(RsStatus.OK)
        );
        final Response get = fixture.ask(
            RqMethod.GET,
            "/manifests/" + DockerProxyCooldownCacheTest.header(head, "Docker-Content-Digest")
        );
        MatcherAssert.assertThat(
            "the digest it resolves to is refused with the cooldown answer",
            get.status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        fixture.decided(DockerProxyCooldownCacheTest.TAG);
        fixture.leftNoManifest();
    }

    @Test
    @Timeout(120)
    void containerdPullOfAllowedVersionIndexesTheTagOnce() throws Exception {
        final Fixture fixture = new Fixture(false);
        final Response head = fixture.ask(
            RqMethod.HEAD, "/manifests/" + DockerProxyCooldownCacheTest.TAG
        );
        final Response get = fixture.ask(
            RqMethod.GET,
            "/manifests/" + DockerProxyCooldownCacheTest.header(head, "Docker-Content-Digest")
        );
        MatcherAssert.assertThat(
            "the digest the tag resolves to is served",
            get.status(), new IsEqual<>(RsStatus.OK)
        );
        final ManifestReference ref = ManifestReference.from(DockerProxyCooldownCacheTest.TAG);
        DockerProxyCooldownCacheTest.eventually(
            () -> fixture.cached().manifests().get(ref).join().isPresent()
                && !fixture.events.isEmpty()
        );
        MatcherAssert.assertThat(
            "the tag is stored",
            fixture.cached().manifests().get(ref).join().isPresent(), new IsEqual<>(true)
        );
        // An event that should not exist comes from a callback after the
        // answers: it gets the time to show itself.
        for (int idx = 0; idx < 20; idx += 1) {
            MatcherAssert.assertThat(
                "and queued for the search index once",
                fixture.events.stream().map(ArtifactEvent::artifactVersion).toList(),
                new IsEqual<>(List.of(DockerProxyCooldownCacheTest.TAG))
            );
            TimeUnit.MILLISECONDS.sleep(15L);
        }
    }

    /**
     * The one value of a response header.
     *
     * @param res Response
     * @param name Header name
     * @return Header value
     */
    private static String header(final Response res, final String name) {
        return res.headers().find(name).get(0).getValue();
    }

    /**
     * Image manifest of the image config and one layer.
     *
     * @param layer Layer bytes
     * @return Manifest JSON
     */
    private static byte[] manifest(final byte[] layer) {
        return String.join(
            "",
            "{\"schemaVersion\":2,\"mediaType\":\"", DockerProxyCooldownCacheTest.MEDIA, "\",",
            "\"config\":{\"mediaType\":\"application/vnd.docker.container.image.v1+json\",",
            "\"size\":", String.valueOf(DockerProxyCooldownCacheTest.CONFIG.length), ",",
            "\"digest\":\"", new Digest.Sha256(DockerProxyCooldownCacheTest.CONFIG).string(),
            "\"},",
            "\"layers\":[{\"mediaType\":\"application/vnd.docker.image.rootfs.diff.tar.gzip\",",
            "\"size\":", String.valueOf(layer.length), ",",
            "\"digest\":\"", new Digest.Sha256(layer).string(), "\"}]}"
        ).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Wait for a state that follows the answer: a cache write is committed
     * after the answer. The state is polled, never timed.
     *
     * @param state Awaited state
     * @throws InterruptedException If interrupted
     */
    private static void eventually(final BooleanSupplier state) throws InterruptedException {
        for (int idx = 0; idx < 400 && !state.getAsBoolean(); idx += 1) {
            TimeUnit.MILLISECONDS.sleep(25L);
        }
    }

    /**
     * A Docker proxy over an in-memory storage, an upstream registry and a
     * cooldown that blocks the image or allows it.
     *
     * @since 2.2.15
     */
    private static final class Fixture {

        /**
         * The storage the repository's storage is a part of.
         */
        private final Storage storage;

        /**
         * The upstream registry.
         */
        private final Upstream upstream;

        /**
         * Events queued for the search index.
         */
        private final Queue<ArtifactEvent> events;

        /**
         * Cooldown.
         */
        private final Cooldown cooldown;

        /**
         * Repository configuration.
         */
        private final RepoConfig cfg;

        /**
         * The proxy.
         */
        private final Slice proxy;

        /**
         * Ctor.
         *
         * @param blocks Whether cooldown blocks the image at first
         * @throws IOException On error
         */
        Fixture(final boolean blocks) throws IOException {
            this.storage = new InMemoryStorage();
            this.upstream = new Upstream();
            this.events = new ConcurrentLinkedQueue<>();
            this.cooldown = new Cooldown(blocks);
            final Storage memory = this.storage;
            this.cfg = RepoConfig.from(
                Yaml.createYamlInput(
                    String.join(
                        "\n",
                        "repo:",
                        "  type: docker-proxy",
                        "  remotes:",
                        "    - url: https://upstream.example",
                        "  storage:",
                        "    type: memory"
                    )
                ).readYamlMapping(),
                new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
                new Key.From(DockerProxyCooldownCacheTest.REPO),
                new StoragesCache() {
                    @Override
                    public Storage storage(final YamlMapping yaml) {
                        return memory;
                    }
                },
                false
            );
            this.proxy = new DockerProxy(
                this.upstream, this.cfg, Policy.FREE,
                (user, pass) -> Optional.of(new AuthUser(user, "test")),
                token -> CompletableFuture.completedFuture(Optional.empty()),
                Optional.of(this.events), this.cooldown
            );
        }

        /**
         * A client's request, its body read.
         *
         * @param path Path below the image name
         * @return Status of the answer
         * @throws Exception On error
         */
        RsStatus get(final String path) throws Exception {
            return this.ask(RqMethod.GET, path).status();
        }

        /**
         * A client's request, its body read.
         *
         * @param method Request method
         * @param path Path below the image name
         * @return Answer, its body read
         * @throws Exception On error
         */
        Response ask(final RqMethod method, final String path) throws Exception {
            final Response res = this.proxy.response(
                new RequestLine(
                    method,
                    String.format(
                        "/%s/%s%s",
                        DockerProxyCooldownCacheTest.REPO, DockerProxyCooldownCacheTest.IMAGE,
                        path
                    )
                ),
                Headers.from(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(
                        "alice:secret".getBytes(StandardCharsets.UTF_8)
                    )
                ),
                Content.EMPTY
            ).get(60, TimeUnit.SECONDS);
            res.body().asBytesFuture().get(60, TimeUnit.SECONDS);
            return res;
        }

        /**
         * Wait until cooldown was asked about a version: a decision a HEAD
         * leads to is made after the answer.
         *
         * @param version Version
         * @throws InterruptedException If interrupted
         */
        void decided(final String version) throws InterruptedException {
            DockerProxyCooldownCacheTest.eventually(
                () -> this.cooldown.versions.contains(version)
            );
        }

        /**
         * No manifest of the image is in the storage and no event for the
         * search index was queued. A write would come from a callback after
         * the answer: one that should not exist gets the time to show
         * itself. The image config read to date the image may be cached:
         * a blob is content addressed, has no row in the search index and
         * is served by digest only.
         *
         * @throws Exception On error
         */
        void leftNoManifest() throws Exception {
            final String config = new Digest.Sha256(DockerProxyCooldownCacheTest.CONFIG).hex();
            for (int idx = 0; idx < 20; idx += 1) {
                MatcherAssert.assertThat(
                    "no manifest of a version in cooldown is stored",
                    this.storage.list(Key.ROOT).join().stream()
                        .map(Key::string)
                        .filter(key -> !key.contains(config))
                        .toList(),
                    new IsEqual<>(List.of())
                );
                TimeUnit.MILLISECONDS.sleep(15L);
            }
            MatcherAssert.assertThat(
                "and no event for the search index is queued",
                this.events.size(), new IsEqual<>(0)
            );
        }

        /**
         * The image in the proxy's cache storage.
         *
         * @return Repo over the storage, never asking the upstream
         */
        Repo cached() {
            return new AstoDocker(
                DockerProxyCooldownCacheTest.REPO,
                new SubStorage(RegistryRoot.V2, this.cfg.storage())
            ).repo(DockerProxyCooldownCacheTest.IMAGE);
        }
    }

    /**
     * Upstream registry: the image under its tag and its digest, its config
     * and layer, and an image index of the image under the tag
     * {@code multi}.
     *
     * @since 2.2.15
     */
    private static final class Upstream implements ClientSlices, Slice {

        /**
         * Requests got: method and path.
         */
        private final List<String> requests;

        /**
         * Manifests by tag and by digest: media type and body.
         */
        private final Map<String, Map.Entry<String, byte[]>> manifests;

        /**
         * Blobs by digest.
         */
        private final Map<String, byte[]> blobs;

        Upstream() {
            this.requests = new CopyOnWriteArrayList<>();
            this.manifests = new ConcurrentHashMap<>();
            this.blobs = new ConcurrentHashMap<>();
            this.tag(
                DockerProxyCooldownCacheTest.TAG, DockerProxyCooldownCacheTest.MEDIA,
                DockerProxyCooldownCacheTest.MANIFEST
            );
            this.tag(
                DockerProxyCooldownCacheTest.MULTI, DockerProxyCooldownCacheTest.INDEX_MEDIA,
                DockerProxyCooldownCacheTest.INDEX
            );
            this.blob(DockerProxyCooldownCacheTest.CONFIG);
            this.blob(DockerProxyCooldownCacheTest.LAYER);
        }

        /**
         * Hold a manifest under a tag and under its digest.
         *
         * @param tag Tag
         * @param media Media type of the manifest
         * @param body Manifest
         */
        void tag(final String tag, final String media, final byte[] body) {
            final Map.Entry<String, byte[]> doc = Map.entry(media, body);
            this.manifests.put(tag, doc);
            this.manifests.put(new Digest.Sha256(body).string(), doc);
        }

        /**
         * Hold a blob.
         *
         * @param body Blob
         */
        void blob(final byte[] body) {
            this.blobs.put(new Digest.Sha256(body).string(), body);
        }

        /**
         * How often the upstream was asked.
         *
         * @param method Request method
         * @param path Path below the image name
         * @return Number of requests
         */
        int asked(final String method, final String path) {
            final String line = String.format(
                "%s /v2/%s%s", method, DockerProxyCooldownCacheTest.IMAGE, path
            );
            return (int) this.requests.stream().filter(line::equals).count();
        }

        @Override
        public Slice http(final String host) {
            return this;
        }

        @Override
        public Slice http(final String host, final int port) {
            return this;
        }

        @Override
        public Slice https(final String host) {
            return this;
        }

        @Override
        public Slice https(final String host, final int port) {
            return this;
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final String path = line.uri().getPath();
            this.requests.add(String.format("%s %s", line.method().value(), path));
            return body.asBytesFuture().thenApply(
                ignored -> {
                    final String ref = path.substring(path.lastIndexOf('/') + 1);
                    final Response res;
                    if (path.contains("/manifests/") && this.manifests.containsKey(ref)) {
                        final Map.Entry<String, byte[]> doc = this.manifests.get(ref);
                        res = ResponseBuilder.ok()
                            .header("Content-Type", doc.getKey())
                            .header(
                                "Docker-Content-Digest",
                                new Digest.Sha256(doc.getValue()).string()
                            )
                            .body(doc.getValue())
                            .build();
                    } else if (path.contains("/blobs/") && this.blobs.containsKey(ref)) {
                        if (line.method() == RqMethod.HEAD) {
                            res = ResponseBuilder.ok()
                                .header(
                                    "Content-Length",
                                    String.valueOf(this.blobs.get(ref).length)
                                )
                                .build();
                        } else {
                            res = ResponseBuilder.ok().body(this.blobs.get(ref)).build();
                        }
                    } else {
                        res = ResponseBuilder.notFound().build();
                    }
                    return res;
                }
            );
        }
    }

    /**
     * Cooldown that blocks every version of the image while it holds a
     * block, and allows every version otherwise.
     *
     * @since 2.2.15
     */
    private static final class Cooldown implements CooldownService {

        /**
         * Whether the image is blocked.
         */
        private final AtomicBoolean blocks;

        /**
         * Versions cooldown was asked about, in order.
         */
        private final List<String> versions;

        /**
         * Ctor.
         *
         * @param blocks Whether the image is blocked at first
         */
        Cooldown(final boolean blocks) {
            this.blocks = new AtomicBoolean(blocks);
            this.versions = new CopyOnWriteArrayList<>();
        }

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            this.versions.add(request.version());
            final CooldownResult res;
            if (this.blocks.get()) {
                final Instant now = Instant.now();
                res = CooldownResult.blocked(
                    new CooldownBlock(
                        request.repoType(), request.repoName(), request.artifact(),
                        request.version(), CooldownReason.FRESH_RELEASE, now,
                        now.plusSeconds(3600L), List.of()
                    )
                );
            } else {
                res = CooldownResult.allowed();
            }
            return CompletableFuture.completedFuture(res);
        }

        @Override
        public CompletableFuture<Void> unblock(
            final String repoType, final String repoName,
            final String artifact, final String version, final String actor
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unblockAll(
            final String repoType, final String repoName, final String actor
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<CooldownBlock>> activeBlocks(
            final String repoType, final String repoName
        ) {
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
