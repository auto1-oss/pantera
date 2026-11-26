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
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.asto.RegistryRoot;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.RequestTrace;
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
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A Troubleshoot replay asks a Docker proxy what a client would get. It
 * must leave the proxy as it found it: the manifest or blob the upstream
 * has is answered, nothing is written into the repository's storage, no
 * event for the search index is queued and no release date is recorded
 * for the repository. A client's request afterwards is cached as before.
 *
 * @since 2.2.15
 */
final class DockerProxyReplayTest {

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
     * Day the image was built, as its config says.
     */
    private static final Instant CREATED = Instant.parse("2020-02-03T04:05:06Z");

    /**
     * Image config.
     */
    private static final byte[] CONFIG = String.format(
        "{\"architecture\":\"amd64\",\"created\":\"%s\"}", DockerProxyReplayTest.CREATED
    ).getBytes(StandardCharsets.UTF_8);

    /**
     * Image layer.
     */
    private static final byte[] LAYER = "layer bytes".getBytes(StandardCharsets.UTF_8);

    /**
     * Digest of the image config.
     */
    private static final String CONFIG_DIGEST =
        new Digest.Sha256(DockerProxyReplayTest.CONFIG).string();

    /**
     * Digest of the image layer.
     */
    private static final String LAYER_DIGEST =
        new Digest.Sha256(DockerProxyReplayTest.LAYER).string();

    /**
     * Media type of the manifest.
     */
    private static final String MEDIA = "application/vnd.docker.distribution.manifest.v2+json";

    /**
     * Image manifest.
     */
    private static final byte[] MANIFEST = String.join(
        "",
        "{\"schemaVersion\":2,\"mediaType\":\"", DockerProxyReplayTest.MEDIA, "\",",
        "\"config\":{\"mediaType\":\"application/vnd.docker.container.image.v1+json\",",
        "\"size\":", String.valueOf(DockerProxyReplayTest.CONFIG.length), ",",
        "\"digest\":\"", DockerProxyReplayTest.CONFIG_DIGEST, "\"},",
        "\"layers\":[{\"mediaType\":\"application/vnd.docker.image.rootfs.diff.tar.gzip\",",
        "\"size\":", String.valueOf(DockerProxyReplayTest.LAYER.length), ",",
        "\"digest\":\"", DockerProxyReplayTest.LAYER_DIGEST, "\"}]}"
    ).getBytes(StandardCharsets.UTF_8);

    /**
     * Digest of the image manifest.
     */
    private static final String MANIFEST_DIGEST =
        new Digest.Sha256(DockerProxyReplayTest.MANIFEST).string();

    /**
     * Tag of the multi-arch image.
     */
    private static final String MULTI = "multi";

    /**
     * Media type of the image index.
     */
    private static final String INDEX_MEDIA = "application/vnd.oci.image.index.v1+json";

    /**
     * Image index of the multi-arch tag: its one platform is the image.
     */
    private static final byte[] INDEX = String.join(
        "",
        "{\"schemaVersion\":2,\"mediaType\":\"", DockerProxyReplayTest.INDEX_MEDIA, "\",",
        "\"manifests\":[{\"mediaType\":\"", DockerProxyReplayTest.MEDIA, "\",",
        "\"size\":", String.valueOf(DockerProxyReplayTest.MANIFEST.length), ",",
        "\"digest\":\"", DockerProxyReplayTest.MANIFEST_DIGEST, "\",",
        "\"platform\":{\"architecture\":\"amd64\",\"os\":\"linux\"}}]}"
    ).getBytes(StandardCharsets.UTF_8);

    /**
     * Digest of the image index.
     */
    private static final String INDEX_DIGEST =
        new Digest.Sha256(DockerProxyReplayTest.INDEX).string();

    @Test
    @Timeout(120)
    void replayOfTagAnswersTheManifestAndStoresNothing() throws Exception {
        final Fixture fixture = new Fixture(new Allowing());
        final String path = "/manifests/" + DockerProxyReplayTest.TAG;
        final ManifestReference ref = ManifestReference.from(DockerProxyReplayTest.TAG);
        fixture.replayAnswers(path, DockerProxyReplayTest.MANIFEST);
        fixture.replayLeftNothing();
        fixture.clientGets(path, DockerProxyReplayTest.MANIFEST);
        DockerProxyReplayTest.eventually(
            () -> fixture.cached().manifests().get(ref).join().isPresent()
                && !fixture.events.isEmpty()
        );
        MatcherAssert.assertThat(
            "a client's request afterwards stores the manifest under its tag as before",
            fixture.cached().manifests().get(ref).join().isPresent(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "and queues the event of the tag for the search index",
            fixture.events.stream().map(ArtifactEvent::artifactVersion).toList(),
            new IsEqual<>(List.of(DockerProxyReplayTest.TAG))
        );
        MatcherAssert.assertThat(
            "the upstream was asked for the tag once by the replay and once by the client",
            fixture.asked("GET", path), new IsEqual<>(2)
        );
    }

    @Test
    @Timeout(120)
    void replayOfDigestAnswersTheManifestAndStoresNothing() throws Exception {
        final Fixture fixture = new Fixture(new Allowing());
        final String path = "/manifests/" + DockerProxyReplayTest.MANIFEST_DIGEST;
        final ManifestReference ref = ManifestReference.from(
            new Digest.FromString(DockerProxyReplayTest.MANIFEST_DIGEST)
        );
        fixture.replayAnswers(path, DockerProxyReplayTest.MANIFEST);
        fixture.replayLeftNothing();
        fixture.clientGets(path, DockerProxyReplayTest.MANIFEST);
        DockerProxyReplayTest.eventually(
            () -> fixture.cached().manifests().get(ref).join().isPresent()
        );
        MatcherAssert.assertThat(
            "a client's request afterwards stores the manifest under its digest as before",
            fixture.cached().manifests().get(ref).join().isPresent(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the upstream was asked for the digest once by the replay and once by the client",
            fixture.asked("GET", path), new IsEqual<>(2)
        );
    }

    @Test
    @Timeout(120)
    void replayOfBlobAnswersTheBlobAndStoresNothing() throws Exception {
        final Fixture fixture = new Fixture(new Allowing());
        final String path = "/blobs/" + DockerProxyReplayTest.LAYER_DIGEST;
        final Digest digest = new Digest.FromString(DockerProxyReplayTest.LAYER_DIGEST);
        fixture.replayAnswers(path, DockerProxyReplayTest.LAYER);
        fixture.replayLeftNothing();
        fixture.clientGets(path, DockerProxyReplayTest.LAYER);
        DockerProxyReplayTest.eventually(
            () -> fixture.cached().layers().get(digest).join().isPresent()
        );
        MatcherAssert.assertThat(
            "a client's request afterwards stores the blob as before",
            fixture.cached().layers().get(digest).join().isPresent(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the upstream was asked for the blob once by the replay and once by the client",
            fixture.asked("GET", path), new IsEqual<>(2)
        );
        final Response held = fixture.replay(path);
        MatcherAssert.assertThat(
            "a replay of a blob the proxy holds is served the blob",
            new String(
                held.body().asBytesFuture().get(60, TimeUnit.SECONDS), StandardCharsets.UTF_8
            ),
            new IsEqual<>(new String(DockerProxyReplayTest.LAYER, StandardCharsets.UTF_8))
        );
        MatcherAssert.assertThat(
            "from the cache, without asking the upstream, as for a client",
            fixture.asked("GET", path) + fixture.asked("HEAD", path), new IsEqual<>(4)
        );
    }

    @Test
    @Timeout(120)
    void replayOfBlockedTagIsRefusedAsForAClientAndStoresNothing() throws Exception {
        final Allowing cooldown = new Blocking();
        final Fixture fixture = new Fixture(cooldown);
        final Response replay = fixture.replay("/manifests/" + DockerProxyReplayTest.TAG);
        replay.body().asBytesFuture().get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the replay of a tag in cooldown is refused like a client's request",
            replay.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "cooldown decided on the tag and on the digest it resolves to",
            cooldown.versions,
            new IsEqual<>(
                List.of(DockerProxyReplayTest.TAG, DockerProxyReplayTest.MANIFEST_DIGEST)
            )
        );
        MatcherAssert.assertThat(
            "with the day the image was built, read from its config",
            cooldown.dates,
            new IsEqual<>(
                List.of(
                    Optional.of(DockerProxyReplayTest.CREATED),
                    Optional.of(DockerProxyReplayTest.CREATED)
                )
            )
        );
        fixture.replayLeftNothing();
    }

    @Test
    @Timeout(120)
    void replayOfMultiArchTagStoresNeitherTheIndexNorWhatDatesIt() throws Exception {
        final Allowing cooldown = new Allowing();
        final Fixture fixture = new Fixture(cooldown);
        final String path = "/manifests/" + DockerProxyReplayTest.MULTI;
        fixture.replayAnswers(path, DockerProxyReplayTest.INDEX);
        MatcherAssert.assertThat(
            "cooldown decided on the tag and on the digest of the index",
            cooldown.versions,
            new IsEqual<>(
                List.of(DockerProxyReplayTest.MULTI, DockerProxyReplayTest.INDEX_DIGEST)
            )
        );
        MatcherAssert.assertThat(
            "with the day its first platform was built: the replay read that manifest"
                + " and its config from the upstream",
            cooldown.dates,
            new IsEqual<>(
                List.of(
                    Optional.of(DockerProxyReplayTest.CREATED),
                    Optional.of(DockerProxyReplayTest.CREATED)
                )
            )
        );
        fixture.replayLeftNothing();
    }

    @Test
    @Timeout(120)
    void replayRecordsNoReleaseDateForTheRepository() throws Exception {
        final Allowing cooldown = new Allowing();
        final Fixture fixture = new Fixture(cooldown);
        fixture.replayAnswers(
            "/manifests/" + DockerProxyReplayTest.TAG, DockerProxyReplayTest.MANIFEST
        );
        MatcherAssert.assertThat(
            "the replay evaluates cooldown with the day the image was built",
            cooldown.dates,
            new IsEqual<>(
                List.of(
                    Optional.of(DockerProxyReplayTest.CREATED),
                    Optional.of(DockerProxyReplayTest.CREATED)
                )
            )
        );
        // A client's request for another image shows what the repository
        // itself remembers: cooldown is handed the repository's inspector.
        final Response other = fixture.proxy.response(
            new RequestLine(
                RqMethod.GET,
                String.format("/%s/library/alpine/manifests/3.20", DockerProxyReplayTest.REPO)
            ),
            Fixture.alice(), Content.EMPTY
        ).get(60, TimeUnit.SECONDS);
        other.body().asBytesFuture().get(60, TimeUnit.SECONDS);
        final CooldownInspector repository =
            cooldown.inspectors.get(cooldown.inspectors.size() - 1);
        MatcherAssert.assertThat(
            "the repository does not know the date the replay read",
            repository.releaseDate(DockerProxyReplayTest.IMAGE, DockerProxyReplayTest.TAG)
                .get(60, TimeUnit.SECONDS),
            new IsEqual<>(Optional.empty())
        );
        MatcherAssert.assertThat(
            "nor that of the digest",
            repository.releaseDate(
                DockerProxyReplayTest.IMAGE, DockerProxyReplayTest.MANIFEST_DIGEST
            ).get(60, TimeUnit.SECONDS),
            new IsEqual<>(Optional.empty())
        );
        MatcherAssert.assertThat(
            "a client's request is dated for the repository as before",
            repository.releaseDate("library/alpine", "3.20").get(60, TimeUnit.SECONDS),
            new IsEqual<>(Optional.of(DockerProxyReplayTest.CREATED))
        );
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

    /**
     * A Docker proxy over an in-memory storage and an upstream that has one
     * image.
     *
     * @since 2.2.15
     */
    private static final class Fixture {

        /**
         * The storage the repository's storage is a part of.
         */
        private final Storage storage;

        /**
         * Requests the upstream got: method and path.
         */
        private final List<String> upstream;

        /**
         * Events queued for the search index.
         */
        private final Queue<ArtifactEvent> events;

        /**
         * Repository configuration.
         */
        private final RepoConfig cfg;

        /**
         * The proxy.
         */
        private final Slice proxy;

        Fixture(final CooldownService cooldown) throws IOException {
            this.storage = new InMemoryStorage();
            this.upstream = new CopyOnWriteArrayList<>();
            this.events = new ConcurrentLinkedQueue<>();
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
                new Key.From(DockerProxyReplayTest.REPO),
                // Whatever the storage node says, the repository is given
                // the in-memory storage.
                new StoragesCache() {
                    @Override
                    public Storage storage(final YamlMapping yaml) {
                        return memory;
                    }
                },
                false
            );
            this.proxy = new DockerProxy(
                new Upstream(this.upstream), this.cfg, Policy.FREE,
                (user, pass) -> Optional.of(new AuthUser(user, "test")),
                token -> CompletableFuture.completedFuture(Optional.empty()),
                Optional.of(this.events), cooldown
            );
        }

        /**
         * Replay a request as the Troubleshoot page does.
         *
         * @param path Path below the image name
         * @return The proxy's answer
         * @throws Exception On error
         */
        Response replay(final String path) throws Exception {
            final String trace = RequestTrace.instance().open();
            try {
                return this.proxy.response(
                    new RequestLine(RqMethod.GET, Fixture.full(path)),
                    Fixture.alice().copy().add(new Header(RequestTrace.HEADER, trace)),
                    Content.EMPTY
                ).get(60, TimeUnit.SECONDS);
            } finally {
                RequestTrace.instance().close(trace);
            }
        }

        /**
         * A replay answers what the upstream has.
         *
         * @param path Path below the image name
         * @param body Expected body
         * @throws Exception On error
         */
        void replayAnswers(final String path, final byte[] body) throws Exception {
            final Response replay = this.replay(path);
            final byte[] got = replay.body().asBytesFuture().get(60, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                "the replay says the upstream has it",
                replay.status(), new IsEqual<>(RsStatus.OK)
            );
            MatcherAssert.assertThat(
                "and hands on what a client would get",
                new String(got, StandardCharsets.UTF_8),
                new IsEqual<>(new String(body, StandardCharsets.UTF_8))
            );
            MatcherAssert.assertThat(
                "the replay asked the upstream as for a client",
                this.asked("GET", path), new IsEqual<>(1)
            );
        }

        /**
         * The replay left the repository as it found it. A write would come
         * from a callback after the answer: one that should not exist gets
         * the time to show itself.
         *
         * @throws Exception On error
         */
        void replayLeftNothing() throws Exception {
            for (int idx = 0; idx < 20; idx += 1) {
                MatcherAssert.assertThat(
                    "the replay writes nothing into the storage",
                    this.stored(), new IsEqual<>(List.of())
                );
                TimeUnit.MILLISECONDS.sleep(15L);
            }
            MatcherAssert.assertThat(
                "and queues no event for the search index",
                this.events.size(), new IsEqual<>(0)
            );
        }

        /**
         * A client's request is answered from the upstream.
         *
         * @param path Path below the image name
         * @param body Expected body
         * @throws Exception On error
         */
        void clientGets(final String path, final byte[] body) throws Exception {
            final Response real = this.proxy.response(
                new RequestLine(RqMethod.GET, Fixture.full(path)),
                Fixture.alice(), Content.EMPTY
            ).get(60, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                "a client's request after the replay is served from the upstream",
                new String(
                    real.body().asBytesFuture().get(60, TimeUnit.SECONDS),
                    StandardCharsets.UTF_8
                ),
                new IsEqual<>(new String(body, StandardCharsets.UTF_8))
            );
        }

        /**
         * The image in the proxy's cache storage.
         *
         * @return Repo over the storage, never asking the upstream
         */
        Repo cached() {
            return new AstoDocker(
                DockerProxyReplayTest.REPO,
                new SubStorage(RegistryRoot.V2, this.cfg.storage())
            ).repo(DockerProxyReplayTest.IMAGE);
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
                "%s /v2/%s%s", method, DockerProxyReplayTest.IMAGE, path
            );
            return (int) this.upstream.stream().filter(line::equals).count();
        }

        /**
         * Keys in the storage, of this repository and of any other.
         *
         * @return All keys
         */
        private List<String> stored() {
            return this.storage.list(Key.ROOT).join().stream().map(Key::string).toList();
        }

        private static String full(final String path) {
            return String.format(
                "/%s/%s%s", DockerProxyReplayTest.REPO, DockerProxyReplayTest.IMAGE, path
            );
        }

        private static Headers alice() {
            return Headers.from(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString(
                    "alice:secret".getBytes(StandardCharsets.UTF_8)
                )
            );
        }
    }

    /**
     * Upstream registry holding one manifest, under every image name, tag
     * and digest, with its config and its layer, and an image index of that
     * manifest under the tag {@code multi}.
     *
     * @since 2.2.15
     */
    private static final class Upstream implements ClientSlices, Slice {

        /**
         * Requests got: method and path.
         */
        private final List<String> requests;

        Upstream(final List<String> requests) {
            this.requests = requests;
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
                    final Response res;
                    if (path.endsWith("/manifests/" + DockerProxyReplayTest.MULTI)) {
                        res = ResponseBuilder.ok()
                            .header("Content-Type", DockerProxyReplayTest.INDEX_MEDIA)
                            .header("Docker-Content-Digest", DockerProxyReplayTest.INDEX_DIGEST)
                            .body(DockerProxyReplayTest.INDEX)
                            .build();
                    } else if (path.contains("/manifests/")) {
                        res = ResponseBuilder.ok()
                            .header("Content-Type", DockerProxyReplayTest.MEDIA)
                            .header(
                                "Docker-Content-Digest", DockerProxyReplayTest.MANIFEST_DIGEST
                            )
                            .body(DockerProxyReplayTest.MANIFEST)
                            .build();
                    } else if (path.endsWith("/blobs/" + DockerProxyReplayTest.CONFIG_DIGEST)) {
                        res = Upstream.blob(line, DockerProxyReplayTest.CONFIG);
                    } else if (path.endsWith("/blobs/" + DockerProxyReplayTest.LAYER_DIGEST)) {
                        res = Upstream.blob(line, DockerProxyReplayTest.LAYER);
                    } else {
                        res = ResponseBuilder.notFound().build();
                    }
                    return res;
                }
            );
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

    /**
     * Cooldown that allows everything and keeps what it was asked and what
     * the inspector it was given knew at that moment.
     *
     * @since 2.2.15
     */
    private static class Allowing implements CooldownService {

        /**
         * Versions evaluated.
         */
        protected final List<String> versions = new CopyOnWriteArrayList<>();

        /**
         * Release date the inspector had for each evaluated version.
         */
        protected final List<Optional<Instant>> dates = new CopyOnWriteArrayList<>();

        /**
         * Inspector of each evaluation.
         */
        protected final List<CooldownInspector> inspectors = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            return inspector.releaseDate(request.artifact(), request.version()).thenApply(
                date -> {
                    this.versions.add(request.version());
                    this.dates.add(date);
                    this.inspectors.add(inspector);
                    return this.verdict(request);
                }
            );
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

        /**
         * The decision on a request.
         *
         * @param request Cooldown request
         * @return Allowed
         */
        protected CooldownResult verdict(final CooldownRequest request) {
            return CooldownResult.allowed();
        }
    }

    /**
     * Cooldown that blocks every tag.
     *
     * @since 2.2.15
     */
    private static final class Blocking extends Allowing {

        @Override
        protected CooldownResult verdict(final CooldownRequest request) {
            final CooldownResult res;
            if (request.version().startsWith("sha256:")) {
                res = CooldownResult.allowed();
            } else {
                final Instant now = Instant.now();
                res = CooldownResult.blocked(
                    new CooldownBlock(
                        request.repoType(), request.repoName(), request.artifact(),
                        request.version(), CooldownReason.FRESH_RELEASE, now,
                        now.plusSeconds(3600L), List.of()
                    )
                );
            }
            return res;
        }
    }
}
