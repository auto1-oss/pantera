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
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.ExampleStorage;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.asto.Uploads;
import com.auto1.pantera.docker.cache.DockerProxyCooldownInspector;
import com.auto1.pantera.docker.fake.FakeManifests;
import com.auto1.pantera.docker.fake.FullTagsManifests;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.google.common.base.Stopwatch;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import wtf.g4s8.hamcrest.json.JsonContains;
import wtf.g4s8.hamcrest.json.JsonHas;
import wtf.g4s8.hamcrest.json.JsonValueIs;
import wtf.g4s8.hamcrest.json.StringIsJson;

import org.slf4j.MDC;

import javax.json.Json;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link CacheManifests}.
 */
final class CacheManifestsTest {
    @ParameterizedTest
    @CsvSource({
        "empty,empty,",
        "empty,full,cache",
        "full,empty,origin",
        "faulty,full,cache",
        "full,faulty,origin",
        "faulty,empty,",
        "empty,faulty,",
        "full,full,origin"
    })
    void shouldReturnExpectedValue(
        final String origin,
        final String cache,
        final String expected
    ) {
        final CacheManifests manifests = new CacheManifests(
            "test",
            new SimpleRepo(new FakeManifests(origin, "origin")),
            new SimpleRepo(new FakeManifests(cache, "cache")),
            Optional.empty(), "*", Optional.empty()
        );
        MatcherAssert.assertThat(
            manifests.get(ManifestReference.from("ref"))
                .toCompletableFuture().join()
                .map(Manifest::digest)
                .map(Digest::hex),
            new IsEqual<>(Optional.ofNullable(expected))
        );
    }

    @Test
    void shouldCacheManifest() throws Exception {
        final ManifestReference ref = ManifestReference.from("1");
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final Repo cache = new AstoDocker("registry", new InMemoryStorage())
            .repo("my-cache");
        new CacheManifests("cache-alpine",
            new AstoDocker("registry", new ExampleStorage()).repo("my-alpine"),
            cache,
            Optional.of(events),
            "my-docker-proxy",
            Optional.of(new DockerProxyCooldownInspector())
        ).get(ref).toCompletableFuture().join();
        final Stopwatch stopwatch = Stopwatch.createStarted();
        while (cache.manifests().get(ref).toCompletableFuture().join().isEmpty()) {
            final int timeout = 10;
            if (stopwatch.elapsed(TimeUnit.SECONDS) > timeout) {
                break;
            }
            final int pause = 100;
            Thread.sleep(pause);
        }
        MatcherAssert.assertThat(
            String.format(
                "Manifest is expected to be present, but it was not found after %s seconds",
                stopwatch.elapsed(TimeUnit.SECONDS)
            ),
            cache.manifests().get(ref).toCompletableFuture().join().isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Artifact metadata were added to queue", events.size() >= 1
        );
        // Check that events were created for layers and manifest
        boolean manifestEventFound = false;
        for (ArtifactEvent event : events) {
            MatcherAssert.assertThat(
                "Event should be for cache-alpine", 
                event.artifactName(), 
                new IsEqual<>("cache-alpine")
            );
            // Check if this is the manifest event
            if ("1".equals(event.artifactVersion()) || 
                event.artifactVersion().startsWith("sha256:cb8a924")) {
                manifestEventFound = true;
            }
        }
        MatcherAssert.assertThat(
            "At least one manifest event should be found", 
            manifestEventFound, 
            new IsEqual<>(true)
        );
    }

    @Test
    void doesNotCreateEventForDigestRef() throws Exception {
        final ManifestReference ref = ManifestReference.from(
            new Digest.Sha256("cb8a924afdf0229ef7515d9e5b3024e23b3eb03ddbba287f4a19c6ac90b8d221")
        );
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final Repo cache = new AstoDocker("registry", new InMemoryStorage())
            .repo("my-cache");
        new CacheManifests("cache-alpine",
            new AstoDocker("registry", new ExampleStorage()).repo("my-alpine"),
            cache,
            Optional.of(events),
            "my-docker-proxy",
            Optional.of(new DockerProxyCooldownInspector())
        ).get(ref).toCompletableFuture().join();
        Thread.sleep(500);
        final boolean hasDigestEvent = events.stream().anyMatch(
            e -> e.artifactVersion().startsWith("sha256:")
        );
        MatcherAssert.assertThat(
            "Digest-based refs should NOT create artifact events",
            hasDigestEvent,
            new IsEqual<>(false)
        );
    }

    @Test
    void recordsReleaseTimestampFromConfig() throws Exception {
        final Instant created = Instant.parse("2024-05-01T12:34:56Z");
        final Digest configDigest = new Digest.Sha256("config");
        final byte[] configBytes = Json.createObjectBuilder()
            .add("created", created.toString())
            .build().toString().getBytes();
        final byte[] manifestBytes = Json.createObjectBuilder()
            .add("mediaType", Manifest.MANIFEST_SCHEMA2)
            .add(
                "config",
                Json.createObjectBuilder().add("digest", configDigest.string())
            )
            .add("layers", Json.createArrayBuilder())
            .build().toString().getBytes();
        final Digest manifestDigest = new Digest.Sha256("manifest");
        final Manifest manifest = new Manifest(manifestDigest, manifestBytes);
        final ManifestReference ref = ManifestReference.fromTag("latest");
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final DockerProxyCooldownInspector inspector = new DockerProxyCooldownInspector();
        final Repo origin = new StubRepo(
            new StaticLayers(Map.of(configDigest.string(), new TestBlob(configDigest, configBytes))),
            new FixedManifests(manifest)
        );
        final RecordingLayers cacheLayers = new RecordingLayers();
        final RecordingManifests cacheManifests = new RecordingManifests();
        final Repo cache = new StubRepo(cacheLayers, cacheManifests);
        new CacheManifests(
            "library/haproxy",
            origin,
            cache,
            Optional.of(events),
            "docker-proxy",
            Optional.of(inspector)
        ).get(ref).toCompletableFuture().join();
        ArtifactEvent recorded = null;
        final long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(2);
        while (recorded == null && System.currentTimeMillis() < deadline) {
            recorded = events.poll();
            if (recorded == null) {
                Thread.sleep(10L);
            }
        }
        Assertions.assertNotNull(recorded, "Expected artifact event to be queued");
        Assertions.assertTrue(recorded.releaseDate().isPresent(), "Release date should be present");
        Assertions.assertEquals(created.toEpochMilli(), recorded.releaseDate().orElseThrow());
        Assertions.assertEquals(
            Optional.of(created),
            inspector.releaseDate("library/haproxy", ref.digest()).join()
        );
    }

    /**
     * Regression: when inspector has UNKNOWN (stored by DockerProxyCooldownSlice using
     * pre-auth headers), CacheManifests must ignore it and use MDC user.name instead.
     *
     * Root cause: DockerProxyCooldownSlice resolves user from original request headers
     * which do not contain pantera_login for Bearer token auth, so it stores UNKNOWN.
     * Without the fix, UNKNOWN (non-null) was used directly as effectiveOwner.
     *
     * @since 1.20.13
     */
    @Test
    void ownerResolvesFromMdcWhenInspectorHasUnknown() throws Exception {
        final Digest configDigest = new Digest.Sha256("config");
        final byte[] configBytes = Json.createObjectBuilder()
            .add("created", "2024-01-01T00:00:00Z")
            .build().toString().getBytes();
        final byte[] manifestBytes = Json.createObjectBuilder()
            .add("mediaType", Manifest.MANIFEST_SCHEMA2)
            .add("config", Json.createObjectBuilder().add("digest", configDigest.string()))
            .add("layers", Json.createArrayBuilder())
            .build().toString().getBytes();
        final Manifest manifest = new Manifest(new Digest.Sha256("manifest"), manifestBytes);
        final ManifestReference ref = ManifestReference.fromTag("latest");
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        // Pre-register UNKNOWN in inspector — simulates DockerProxyCooldownSlice behaviour
        // for Bearer-token users where pre-auth headers have no pantera_login.
        final DockerProxyCooldownInspector inspector = new DockerProxyCooldownInspector();
        inspector.register(
            "library/haproxy", "latest", Optional.empty(),
            ArtifactEvent.DEF_OWNER, "docker-proxy", Optional.empty()
        );
        final Repo origin = new StubRepo(
            new StaticLayers(Map.of(configDigest.string(), new TestBlob(configDigest, configBytes))),
            new FixedManifests(manifest)
        );
        MDC.put("user.name", "alice");
        try {
            new CacheManifests(
                "library/haproxy",
                origin,
                new StubRepo(new RecordingLayers(), new RecordingManifests()),
                Optional.of(events),
                "docker-proxy",
                Optional.of(inspector)
            ).get(ref).toCompletableFuture().join();
        } finally {
            MDC.remove("user.name");
        }
        ArtifactEvent event = null;
        final long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(2);
        while (event == null && System.currentTimeMillis() < deadline) {
            event = events.poll();
            if (event == null) {
                Thread.sleep(10L);
            }
        }
        Assertions.assertNotNull(event, "Expected artifact event to be queued");
        Assertions.assertEquals(
            "alice", event.owner(),
            "Owner must be resolved from MDC user.name, not UNKNOWN from inspector"
        );
    }

    /**
     * Regression: UNKNOWN from inspector without any MDC should still yield UNKNOWN.
     * This is correct behaviour for unauthenticated/anonymous pulls.
     *
     * @since 1.20.13
     */
    @Test
    void ownerIsUnknownWhenInspectorHasUnknownAndNoMdc() throws Exception {
        final Digest configDigest = new Digest.Sha256("cfg");
        final byte[] manifestBytes = Json.createObjectBuilder()
            .add("mediaType", Manifest.MANIFEST_SCHEMA2)
            .add("config", Json.createObjectBuilder().add("digest", configDigest.string()))
            .add("layers", Json.createArrayBuilder())
            .build().toString().getBytes();
        final Manifest manifest = new Manifest(new Digest.Sha256("mfst"), manifestBytes);
        final ManifestReference ref = ManifestReference.fromTag("v1.0");
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final DockerProxyCooldownInspector inspector = new DockerProxyCooldownInspector();
        inspector.register(
            "library/nginx", "v1.0", Optional.empty(),
            ArtifactEvent.DEF_OWNER, "docker-proxy", Optional.empty()
        );
        MDC.remove("user.name");
        new CacheManifests(
            "library/nginx",
            new StubRepo(new StaticLayers(Map.of()), new FixedManifests(manifest)),
            new StubRepo(new RecordingLayers(), new RecordingManifests()),
            Optional.of(events),
            "docker-proxy",
            Optional.of(inspector)
        ).get(ref).toCompletableFuture().join();
        ArtifactEvent event = null;
        final long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(2);
        while (event == null && System.currentTimeMillis() < deadline) {
            event = events.poll();
            if (event == null) {
                Thread.sleep(10L);
            }
        }
        Assertions.assertNotNull(event, "Expected artifact event to be queued");
        Assertions.assertEquals(
            ArtifactEvent.DEF_OWNER, event.owner(),
            "Owner must be UNKNOWN when no MDC user is set and inspector has UNKNOWN"
        );
    }

    @Test
    void loadsTagsFromOriginAndCache() {
        final int limit = 3;
        final String name = "tags-test";
        MatcherAssert.assertThat(
            new CacheManifests(
                name,
                new SimpleRepo(
                    new FullTagsManifests(
                        () -> new Content.From("{\"tags\":[\"one\",\"three\",\"four\"]}".getBytes())
                    )
                ),
                new SimpleRepo(
                    new FullTagsManifests(
                        () -> new Content.From("{\"tags\":[\"one\",\"two\"]}".getBytes())
                    )
                ), Optional.empty(), "*", Optional.empty()
            ).tags(Pagination.from("four", limit)).thenCompose(
                tags -> tags.json().asStringFuture()
            ).toCompletableFuture().join(),
            new StringIsJson.Object(
                Matchers.allOf(
                    new JsonHas("name", new JsonValueIs(name)),
                    new JsonHas(
                        "tags",
                        new JsonContains(
                            new JsonValueIs("one"), new JsonValueIs("three"), new JsonValueIs("two")
                        )
                    )
                )
            )
        );
    }

    /**
     * Simple repo implementation.
     */
    private static final class SimpleRepo implements Repo {

        private final Manifests manifests;

        /**
         * @param manifests Manifests.
         */
        private SimpleRepo(final Manifests manifests) {
            this.manifests = manifests;
        }

        @Override
        public Layers layers() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Manifests manifests() {
            return this.manifests;
        }

        @Override
        public Uploads uploads() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StubRepo implements Repo {

        private final Layers layers;

        private final Manifests manifests;

        private StubRepo(final Layers layers, final Manifests manifests) {
            this.layers = layers;
            this.manifests = manifests;
        }

        @Override
        public Layers layers() {
            return this.layers;
        }

        @Override
        public Manifests manifests() {
            return this.manifests;
        }

        @Override
        public Uploads uploads() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StaticLayers implements Layers {

        private final Map<String, Blob> blobs;

        private StaticLayers(final Map<String, Blob> blobs) {
            this.blobs = blobs;
        }

        @Override
        public CompletableFuture<Digest> put(final com.auto1.pantera.docker.asto.BlobSource source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> mount(final Blob blob) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Optional<Blob>> get(final Digest digest) {
            return CompletableFuture.completedFuture(Optional.ofNullable(this.blobs.get(digest.string())));
        }
    }

    private static final class FixedManifests implements Manifests {

        private final Manifest manifest;

        private FixedManifests(final Manifest manifest) {
            this.manifest = manifest;
        }

        @Override
        public CompletableFuture<Manifest> put(final ManifestReference ref, final Content content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Optional<Manifest>> get(final ManifestReference ref) {
            return CompletableFuture.completedFuture(Optional.of(this.manifest));
        }

        @Override
        public CompletableFuture<com.auto1.pantera.docker.Tags> tags(final Pagination pagination) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingLayers implements Layers {

        @Override
        public CompletableFuture<Digest> put(final com.auto1.pantera.docker.asto.BlobSource source) {
            return CompletableFuture.completedFuture(source.digest());
        }

        @Override
        public CompletableFuture<Void> mount(final Blob blob) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Optional<Blob>> get(final Digest digest) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    private static final class RecordingManifests implements Manifests {

        @Override
        public CompletableFuture<Manifest> put(final ManifestReference ref, final Content content) {
            return content.asBytesFuture()
                .thenApply(bytes -> new Manifest(new Digest.Sha256("stored"), bytes));
        }

        @Override
        public CompletableFuture<Optional<Manifest>> get(final ManifestReference ref) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<com.auto1.pantera.docker.Tags> tags(final Pagination pagination) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class TestBlob implements Blob {

        private final Digest digest;

        private final byte[] data;

        private TestBlob(final Digest digest, final byte[] data) {
            this.digest = digest;
            this.data = data;
        }

        @Override
        public Digest digest() {
            return this.digest;
        }

        @Override
        public CompletableFuture<Long> size() {
            return CompletableFuture.completedFuture((long) this.data.length);
        }

        @Override
        public CompletableFuture<Content> content() {
            return CompletableFuture.completedFuture(new Content.From(this.data));
        }
    }
}
