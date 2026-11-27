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
import com.auto1.pantera.docker.cache.TagChecks;
import com.auto1.pantera.docker.cooldown.DockerCooldownResponseFactory;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.log.RequestTrace;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.StorageByAlias;
import com.auto1.pantera.settings.repo.RepoConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A docker-proxy serves a cached tag until it is older than the tag refresh
 * age, then asks the upstream whether the tag moved: a HEAD, and a download
 * only when the digest changed. Through the production wiring of
 * {@link DockerProxy}: in-memory storage, a fake upstream registry, a clock
 * the test moves.
 *
 * @since 2.2.15
 */
@SuppressWarnings("PMD.TooManyMethods")
final class DockerProxyTagRefreshTest {

    /**
     * Repository name.
     */
    private static final String REPO = "hub";

    /**
     * Image name at the upstream.
     */
    private static final String IMAGE = "library/node";

    /**
     * Tag of the image.
     */
    private static final String TAG = "20";

    /**
     * Path of the tag's manifest below the image name.
     */
    private static final String BY_TAG = "/manifests/" + DockerProxyTagRefreshTest.TAG;

    /**
     * Media type of an image manifest.
     */
    private static final String MEDIA = "application/vnd.docker.distribution.manifest.v2+json";

    /**
     * Image config.
     */
    private static final byte[] CONFIG =
        "{\"architecture\":\"amd64\",\"created\":\"2020-02-03T04:05:06Z\"}"
            .getBytes(StandardCharsets.UTF_8);

    /**
     * Layer of the first image the tag names.
     */
    private static final byte[] OLD_LAYER = "old layer".getBytes(StandardCharsets.UTF_8);

    /**
     * Layer of the image the tag moves to.
     */
    private static final byte[] NEW_LAYER = "new layer".getBytes(StandardCharsets.UTF_8);

    /**
     * First image of the tag.
     */
    private static final byte[] OLD = DockerProxyTagRefreshTest.manifest(
        DockerProxyTagRefreshTest.OLD_LAYER
    );

    /**
     * Image the tag moves to.
     */
    private static final byte[] NEW = DockerProxyTagRefreshTest.manifest(
        DockerProxyTagRefreshTest.NEW_LAYER
    );

    /**
     * Refresh age of the tests.
     */
    private static final Duration AGE = Duration.ofHours(1);

    @BeforeAll
    static void registerTheDockerRefusal() {
        CooldownResponseRegistry.instance().register(
            new DockerCooldownResponseFactory(), "docker-proxy"
        );
    }

    @Test
    @Timeout(120)
    void youngCachedTagIsServedWithoutAskingTheUpstream() throws Exception {
        final Fixture fixture = new Fixture(DockerProxyTagRefreshTest.AGE);
        fixture.pulled();
        fixture.clock.pass(DockerProxyTagRefreshTest.AGE.minusMinutes(1));
        MatcherAssert.assertThat(
            "the cached image is served",
            fixture.digest(RqMethod.GET), new IsEqual<>(DockerProxyTagRefreshTest.digest(OLD))
        );
        MatcherAssert.assertThat(
            "the upstream is not asked again",
            List.of(
                fixture.upstream.asked("HEAD", DockerProxyTagRefreshTest.BY_TAG),
                fixture.upstream.asked("GET", DockerProxyTagRefreshTest.BY_TAG)
            ),
            new IsEqual<>(List.of(0, 1))
        );
    }

    @Test
    @Timeout(120)
    void oldTagThatDidNotMoveCostsOneHeadAndNoDownload() throws Exception {
        final Fixture fixture = new Fixture(DockerProxyTagRefreshTest.AGE);
        fixture.pulled();
        fixture.clock.pass(DockerProxyTagRefreshTest.AGE.plusMinutes(1));
        MatcherAssert.assertThat(
            "the cached image is served",
            fixture.digest(RqMethod.GET), new IsEqual<>(DockerProxyTagRefreshTest.digest(OLD))
        );
        fixture.digest(RqMethod.GET);
        MatcherAssert.assertThat(
            "the upstream is asked with one HEAD, and the tag counts as checked after it",
            List.of(
                fixture.upstream.asked("HEAD", DockerProxyTagRefreshTest.BY_TAG),
                fixture.upstream.asked("GET", DockerProxyTagRefreshTest.BY_TAG)
            ),
            new IsEqual<>(List.of(1, 1))
        );
        MatcherAssert.assertThat(
            "nothing is stored or indexed again",
            fixture.events.size(), new IsEqual<>(1)
        );
    }

    @Test
    @Timeout(120)
    void oldTagThatMovedIsServedStoredAndIndexedAnew() throws Exception {
        final Fixture fixture = new Fixture(DockerProxyTagRefreshTest.AGE);
        fixture.pulled();
        fixture.upstream.tag(DockerProxyTagRefreshTest.NEW);
        fixture.clock.pass(DockerProxyTagRefreshTest.AGE.plusMinutes(1));
        MatcherAssert.assertThat(
            "the image the tag names now is served",
            fixture.digest(RqMethod.GET), new IsEqual<>(DockerProxyTagRefreshTest.digest(NEW))
        );
        MatcherAssert.assertThat(
            "the cache holds it under the tag",
            fixture.cachedDigest(), new IsEqual<>(DockerProxyTagRefreshTest.digest(NEW))
        );
        MatcherAssert.assertThat(
            "the tag is indexed again",
            fixture.events.stream().map(ArtifactEvent::artifactVersion).toList(),
            new IsEqual<>(List.of(DockerProxyTagRefreshTest.TAG, DockerProxyTagRefreshTest.TAG))
        );
    }

    @Test
    @Timeout(120)
    void headOfAnOldTagThatMovedNamesTheNewDigest() throws Exception {
        final Fixture fixture = new Fixture(DockerProxyTagRefreshTest.AGE);
        fixture.pulled();
        fixture.upstream.tag(DockerProxyTagRefreshTest.NEW);
        fixture.clock.pass(DockerProxyTagRefreshTest.AGE.plusMinutes(1));
        final String digest = fixture.digest(RqMethod.HEAD);
        MatcherAssert.assertThat(
            "containerd resolves the tag to the image it names now",
            digest, new IsEqual<>(DockerProxyTagRefreshTest.digest(NEW))
        );
        MatcherAssert.assertThat(
            "and gets that image by its digest",
            fixture.ask(RqMethod.GET, "/manifests/" + digest).status(),
            new IsEqual<>(RsStatus.OK)
        );
    }

    @Test
    @Timeout(120)
    void oldTagIsServedFromTheCacheWhenTheUpstreamFails() throws Exception {
        final Fixture fixture = new Fixture(DockerProxyTagRefreshTest.AGE);
        fixture.pulled();
        fixture.upstream.down.set(true);
        fixture.clock.pass(DockerProxyTagRefreshTest.AGE.plusMinutes(1));
        MatcherAssert.assertThat(
            "the cached image stands in for the upstream",
            fixture.digest(RqMethod.GET), new IsEqual<>(DockerProxyTagRefreshTest.digest(OLD))
        );
        fixture.digest(RqMethod.GET);
        MatcherAssert.assertThat(
            "a failed check counts as a check: the upstream is asked once per age",
            fixture.upstream.asked("HEAD", DockerProxyTagRefreshTest.BY_TAG), new IsEqual<>(1)
        );
    }

    @Test
    @Timeout(120)
    void tagThatMovedToAnImageInCooldownKeepsServingTheCachedImage() throws Exception {
        final Fixture fixture = new Fixture(DockerProxyTagRefreshTest.AGE);
        fixture.pulled();
        fixture.upstream.tag(DockerProxyTagRefreshTest.NEW);
        fixture.cooldown.blocked.add(DockerProxyTagRefreshTest.digest(NEW));
        fixture.clock.pass(DockerProxyTagRefreshTest.AGE.plusMinutes(1));
        MatcherAssert.assertThat(
            "a GET of the tag gets the cached image",
            fixture.digest(RqMethod.GET), new IsEqual<>(DockerProxyTagRefreshTest.digest(OLD))
        );
        fixture.clock.pass(DockerProxyTagRefreshTest.AGE.plusMinutes(1));
        MatcherAssert.assertThat(
            "a HEAD of the tag too",
            fixture.digest(RqMethod.HEAD), new IsEqual<>(DockerProxyTagRefreshTest.digest(OLD))
        );
        MatcherAssert.assertThat(
            "the image in cooldown is not stored under the tag",
            fixture.cachedDigest(), new IsEqual<>(DockerProxyTagRefreshTest.digest(OLD))
        );
        MatcherAssert.assertThat(
            "nor indexed",
            fixture.events.size(), new IsEqual<>(1)
        );
    }

    @Test
    @Timeout(120)
    void tagIsCheckedOnEveryRequestWithAgeZero() throws Exception {
        final Fixture fixture = new Fixture(Duration.ZERO);
        fixture.pulled();
        fixture.digest(RqMethod.GET);
        fixture.digest(RqMethod.HEAD);
        MatcherAssert.assertThat(
            fixture.upstream.asked("HEAD", DockerProxyTagRefreshTest.BY_TAG), new IsEqual<>(2)
        );
    }

    @Test
    @Timeout(120)
    void digestIsNeverChecked() throws Exception {
        final Fixture fixture = new Fixture(Duration.ZERO);
        final String path = "/manifests/" + DockerProxyTagRefreshTest.digest(OLD);
        fixture.ask(RqMethod.GET, path);
        fixture.stored(ManifestReference.from(DockerProxyTagRefreshTest.digest(OLD)));
        fixture.ask(RqMethod.GET, path);
        fixture.ask(RqMethod.HEAD, path);
        MatcherAssert.assertThat(
            "a digest names one image for ever: the cached copy is served",
            List.of(fixture.upstream.asked("HEAD", path), fixture.upstream.asked("GET", path)),
            new IsEqual<>(List.of(0, 1))
        );
    }

    @Test
    @Timeout(120)
    void concurrentRequestsOfAnOldTagShareOneCheck() throws Exception {
        final Fixture fixture = new Fixture(DockerProxyTagRefreshTest.AGE);
        fixture.pulled();
        fixture.clock.pass(DockerProxyTagRefreshTest.AGE.plusMinutes(1));
        final CountDownLatch gate = new CountDownLatch(1);
        fixture.upstream.held.set(gate);
        final List<CompletableFuture<Response>> answers = new ArrayList<>();
        for (int idx = 0; idx < 8; idx += 1) {
            answers.add(fixture.send(RqMethod.GET, DockerProxyTagRefreshTest.BY_TAG));
        }
        DockerProxyTagRefreshTest.eventually(
            () -> fixture.upstream.asked("HEAD", DockerProxyTagRefreshTest.BY_TAG) > 0
        );
        gate.countDown();
        for (final CompletableFuture<Response> answer : answers) {
            answer.get(60, TimeUnit.SECONDS).body().asBytesFuture().get(60, TimeUnit.SECONDS);
        }
        MatcherAssert.assertThat(
            fixture.upstream.asked("HEAD", DockerProxyTagRefreshTest.BY_TAG), new IsEqual<>(1)
        );
    }

    @Test
    @Timeout(120)
    void replayOfAnOldTagThatMovedStoresNothingAndLeavesTheCheckToTheNextClient()
        throws Exception {
        final Fixture fixture = new Fixture(DockerProxyTagRefreshTest.AGE);
        fixture.pulled();
        fixture.upstream.tag(DockerProxyTagRefreshTest.NEW);
        fixture.clock.pass(DockerProxyTagRefreshTest.AGE.plusMinutes(1));
        final Response replay = fixture.send(
            RqMethod.GET, DockerProxyTagRefreshTest.BY_TAG,
            new Headers().add(RequestTrace.HEADER, RequestTrace.instance().open())
        ).get(60, TimeUnit.SECONDS);
        replay.body().asBytesFuture().get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the replay shows what a client gets: the image the tag names now",
            DockerProxyTagRefreshTest.header(replay, "Docker-Content-Digest"),
            new IsEqual<>(DockerProxyTagRefreshTest.digest(NEW))
        );
        MatcherAssert.assertThat(
            "nothing is stored",
            fixture.cachedDigest(), new IsEqual<>(DockerProxyTagRefreshTest.digest(OLD))
        );
        MatcherAssert.assertThat(
            "the next client's request checks the tag itself and gets the new image",
            fixture.digest(RqMethod.GET), new IsEqual<>(DockerProxyTagRefreshTest.digest(NEW))
        );
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
     * Digest of a manifest.
     *
     * @param manifest Manifest bytes
     * @return Digest
     */
    private static String digest(final byte[] manifest) {
        return new Digest.Sha256(manifest).string();
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
            "{\"schemaVersion\":2,\"mediaType\":\"", DockerProxyTagRefreshTest.MEDIA, "\",",
            "\"config\":{\"mediaType\":\"application/vnd.docker.container.image.v1+json\",",
            "\"size\":", String.valueOf(DockerProxyTagRefreshTest.CONFIG.length), ",",
            "\"digest\":\"", new Digest.Sha256(DockerProxyTagRefreshTest.CONFIG).string(),
            "\"},",
            "\"layers\":[{\"mediaType\":\"application/vnd.docker.image.rootfs.diff.tar.gzip\",",
            "\"size\":", String.valueOf(layer.length), ",",
            "\"digest\":\"", new Digest.Sha256(layer).string(), "\"}]}"
        ).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Wait for a state that follows the answer. The state is polled, never
     * timed.
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
     * A Docker proxy over an in-memory storage, an upstream registry, a
     * cooldown and a clock.
     *
     * @since 2.2.15
     */
    private static final class Fixture {

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
         * Clock of the tag checks.
         */
        private final MovingClock clock;

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
         * @param age Tag refresh age
         * @throws IOException On error
         */
        Fixture(final Duration age) throws IOException {
            final Storage memory = new InMemoryStorage();
            this.upstream = new Upstream();
            this.events = new ConcurrentLinkedQueue<>();
            this.cooldown = new Cooldown();
            this.clock = new MovingClock();
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
                new Key.From(DockerProxyTagRefreshTest.REPO),
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
                Optional.of(this.events), this.cooldown, new TagChecks(() -> age, this.clock)
            );
        }

        /**
         * A first pull of the tag, stored.
         *
         * @throws Exception On error
         */
        void pulled() throws Exception {
            MatcherAssert.assertThat(
                "the first pull gets the tag's image",
                this.digest(RqMethod.GET), new IsEqual<>(DockerProxyTagRefreshTest.digest(OLD))
            );
            this.stored(ManifestReference.from(DockerProxyTagRefreshTest.TAG));
            DockerProxyTagRefreshTest.eventually(() -> !this.events.isEmpty());
        }

        /**
         * Wait until the cache holds a reference.
         *
         * @param ref Reference
         * @throws InterruptedException If interrupted
         */
        void stored(final ManifestReference ref) throws InterruptedException {
            DockerProxyTagRefreshTest.eventually(
                () -> this.cached().manifests().get(ref).join().isPresent()
            );
        }

        /**
         * The digest a request of the tag is answered with.
         *
         * @param method GET or HEAD
         * @return Digest
         * @throws Exception On error
         */
        String digest(final RqMethod method) throws Exception {
            final Response res = this.ask(method, DockerProxyTagRefreshTest.BY_TAG);
            MatcherAssert.assertThat(
                "the tag is served", res.status(), new IsEqual<>(RsStatus.OK)
            );
            return DockerProxyTagRefreshTest.header(res, "Docker-Content-Digest");
        }

        /**
         * Digest of the manifest the cache holds under the tag.
         *
         * @return Digest
         */
        String cachedDigest() {
            return this.cached().manifests()
                .get(ManifestReference.from(DockerProxyTagRefreshTest.TAG)).join()
                .orElseThrow().digest().string();
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
            final Response res = this.send(method, path).get(60, TimeUnit.SECONDS);
            res.body().asBytesFuture().get(60, TimeUnit.SECONDS);
            return res;
        }

        /**
         * A client's request.
         *
         * @param method Request method
         * @param path Path below the image name
         * @return Answer
         */
        CompletableFuture<Response> send(final RqMethod method, final String path) {
            return this.send(method, path, Headers.EMPTY);
        }

        /**
         * A client's request with headers of its own.
         *
         * @param method Request method
         * @param path Path below the image name
         * @param extra Headers
         * @return Answer
         */
        CompletableFuture<Response> send(
            final RqMethod method, final String path, final Headers extra
        ) {
            return this.proxy.response(
                new RequestLine(
                    method,
                    String.format(
                        "/%s/%s%s",
                        DockerProxyTagRefreshTest.REPO, DockerProxyTagRefreshTest.IMAGE, path
                    )
                ),
                extra.copy().add(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(
                        "alice:secret".getBytes(StandardCharsets.UTF_8)
                    )
                ),
                Content.EMPTY
            );
        }

        /**
         * The image in the proxy's cache storage.
         *
         * @return Repo over the storage, never asking the upstream
         */
        Repo cached() {
            return new AstoDocker(
                DockerProxyTagRefreshTest.REPO,
                new SubStorage(RegistryRoot.V2, this.cfg.storage())
            ).repo(DockerProxyTagRefreshTest.IMAGE);
        }
    }

    /**
     * Clock the test moves forward.
     *
     * @since 2.2.15
     */
    private static final class MovingClock extends Clock {

        /**
         * Now.
         */
        private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-11-27T10:00:00Z"));

        /**
         * Let time pass.
         *
         * @param time How much
         */
        void pass(final Duration time) {
            this.now.updateAndGet(instant -> instant.plus(time));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now.get();
        }
    }

    /**
     * Upstream registry: the image under the tag and its digest, its config
     * and layers. It can move the tag, fail every request, or hold its
     * answers until a latch opens.
     *
     * @since 2.2.15
     */
    private static final class Upstream implements ClientSlices, Slice {

        /**
         * Requests got: method and path.
         */
        private final List<String> requests;

        /**
         * Manifests by tag and by digest.
         */
        private final Map<String, byte[]> manifests;

        /**
         * Blobs by digest.
         */
        private final Map<String, byte[]> blobs;

        /**
         * Whether every request fails.
         */
        private final AtomicBoolean down;

        /**
         * Latch an answer waits for, if any.
         */
        private final AtomicReference<CountDownLatch> held;

        Upstream() {
            this.requests = new CopyOnWriteArrayList<>();
            this.manifests = new ConcurrentHashMap<>();
            this.blobs = new ConcurrentHashMap<>();
            this.down = new AtomicBoolean(false);
            this.held = new AtomicReference<>();
            this.tag(DockerProxyTagRefreshTest.OLD);
            this.blobs.put(
                new Digest.Sha256(DockerProxyTagRefreshTest.CONFIG).string(),
                DockerProxyTagRefreshTest.CONFIG
            );
            for (final byte[] layer : List.of(OLD_LAYER, NEW_LAYER)) {
                this.blobs.put(new Digest.Sha256(layer).string(), layer);
            }
        }

        /**
         * Point the tag at a manifest; the manifest stays under its digest.
         *
         * @param body Manifest
         */
        void tag(final byte[] body) {
            this.manifests.put(DockerProxyTagRefreshTest.TAG, body);
            this.manifests.put(new Digest.Sha256(body).string(), body);
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
                "%s /v2/%s%s", method, DockerProxyTagRefreshTest.IMAGE, path
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
            final CountDownLatch latch = this.held.get();
            return body.asBytesFuture().thenApplyAsync(
                ignored -> {
                    if (latch != null) {
                        try {
                            latch.await(60, TimeUnit.SECONDS);
                        } catch (final InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return this.answer(line, path);
                }
            );
        }

        /**
         * Answer to a request.
         *
         * @param line Request line
         * @param path Request path
         * @return Answer
         */
        private Response answer(final RequestLine line, final String path) {
            final String ref = path.substring(path.lastIndexOf('/') + 1);
            final Response res;
            if (this.down.get()) {
                res = ResponseBuilder.internalError().build();
            } else if (path.contains("/manifests/") && this.manifests.containsKey(ref)) {
                final byte[] doc = this.manifests.get(ref);
                final ResponseBuilder builder = ResponseBuilder.ok()
                    .header("Content-Type", DockerProxyTagRefreshTest.MEDIA)
                    .header("Docker-Content-Digest", new Digest.Sha256(doc).string());
                if (line.method() == RqMethod.HEAD) {
                    res = builder.header("Content-Length", String.valueOf(doc.length)).build();
                } else {
                    res = builder.body(doc).build();
                }
            } else if (path.contains("/blobs/") && this.blobs.containsKey(ref)) {
                if (line.method() == RqMethod.HEAD) {
                    res = ResponseBuilder.ok()
                        .header("Content-Length", String.valueOf(this.blobs.get(ref).length))
                        .build();
                } else {
                    res = ResponseBuilder.ok().body(this.blobs.get(ref)).build();
                }
            } else {
                res = ResponseBuilder.notFound().build();
            }
            return res;
        }
    }

    /**
     * Cooldown that blocks the versions it is given and allows the others.
     *
     * @since 2.2.15
     */
    private static final class Cooldown implements CooldownService {

        /**
         * Blocked versions.
         */
        private final Set<String> blocked = ConcurrentHashMap.newKeySet();

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            final CooldownResult res;
            if (this.blocked.contains(request.version())) {
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
