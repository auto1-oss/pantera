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
import com.auto1.pantera.docker.ExampleStorage;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.scheduling.ArtifactEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link CacheManifests#refresh}: a cached tag the origin moved is stored
 * anew when the verdict allows the new manifest; when it refuses, the cached
 * manifest stays, is served, and keeps the tag's release date.
 *
 * @since 2.2.15
 */
final class CacheManifestsRefreshTest {

    /**
     * Image name in the origin.
     */
    private static final String IMAGE = "my-alpine";

    /**
     * Tag of the image.
     */
    private static final ManifestReference TAG = ManifestReference.from("1");

    /**
     * Release date the cached manifest gave the tag.
     */
    private static final Instant OLD = Instant.parse("2001-01-01T00:00:00Z");

    /**
     * Events queued for the search index.
     */
    private Queue<ArtifactEvent> events;

    /**
     * Cooldown inspector of the repository.
     */
    private DockerProxyCooldownInspector inspector;

    /**
     * The caching proxy.
     */
    private CacheDocker docker;

    /**
     * The cache.
     */
    private AstoDocker cache;

    /**
     * Manifest the cache holds under the tag.
     */
    private Manifest cached;

    @BeforeEach
    void setUp() {
        this.events = new ConcurrentLinkedQueue<>();
        this.inspector = new DockerProxyCooldownInspector();
        this.cache = new AstoDocker("registry", new InMemoryStorage());
        this.docker = new CacheDocker(
            new AstoDocker("registry", new ExampleStorage()),
            this.cache,
            Optional.of(this.events),
            Optional.of(this.inspector)
        );
        this.cached = this.cache.repo(CacheManifestsRefreshTest.IMAGE).manifests().putUnchecked(
            CacheManifestsRefreshTest.TAG,
            new Content.From(
                String.join(
                    "",
                    "{\"schemaVersion\":2,",
                    "\"mediaType\":\"application/vnd.docker.distribution.manifest.v2+json\",",
                    "\"config\":{\"mediaType\":\"application/vnd.docker.container.image.v1+json\",",
                    "\"size\":2,\"digest\":\"sha256:",
                    "0000000000000000000000000000000000000000000000000000000000000000\"},",
                    "\"layers\":[]}"
                ).getBytes(StandardCharsets.UTF_8)
            )
        ).join();
        this.inspector.recordRelease(
            CacheManifestsRefreshTest.IMAGE, CacheManifestsRefreshTest.TAG.digest(),
            CacheManifestsRefreshTest.OLD
        );
    }

    @Test
    @Timeout(120)
    void movedTagIsStoredAnewWhenTheVerdictAllowsIt() throws Exception {
        final Manifest served = this.refresh(true);
        MatcherAssert.assertThat(
            "the manifest the origin names now is served",
            served.digest().string(), new IsNot<>(new IsEqual<>(this.cached.digest().string()))
        );
        MatcherAssert.assertThat(
            "and held by the cache under the tag",
            this.held().digest().string(), new IsEqual<>(served.digest().string())
        );
        MatcherAssert.assertThat(
            "the tag is dated as the new manifest",
            this.tagDate(), new IsNot<>(new IsEqual<>(Optional.of(CacheManifestsRefreshTest.OLD)))
        );
        MatcherAssert.assertThat(
            "and indexed again",
            this.events.stream().map(ArtifactEvent::artifactVersion).toList(),
            new IsEqual<>(List.of("1"))
        );
    }

    @Test
    @Timeout(120)
    void movedTagKeepsTheCachedManifestAndItsDateWhenTheVerdictRefusesTheNewOne()
        throws Exception {
        MatcherAssert.assertThat(
            "the cached manifest is served",
            this.refresh(false).digest().string(), new IsEqual<>(this.cached.digest().string())
        );
        MatcherAssert.assertThat(
            "and stays under the tag",
            this.held().digest().string(), new IsEqual<>(this.cached.digest().string())
        );
        MatcherAssert.assertThat(
            "with the date it gave the tag: a cooldown check of the tag finds the old date",
            this.tagDate(), new IsEqual<>(Optional.of(CacheManifestsRefreshTest.OLD))
        );
        MatcherAssert.assertThat(
            "nothing is indexed",
            this.events.size(), new IsEqual<>(0)
        );
    }

    @Test
    @Timeout(120)
    void unchangedTagIsServedFromTheCacheWithoutACopy() throws Exception {
        final Manifest origin = new AstoDocker("registry", new ExampleStorage())
            .repo(CacheManifestsRefreshTest.IMAGE).manifests()
            .get(CacheManifestsRefreshTest.TAG).join().orElseThrow();
        final Manifest served = this.docker
            .deciding(new ManifestCopies((image, ref, manifest) -> CompletableFuture.completedFuture(true)))
            .repo(CacheManifestsRefreshTest.IMAGE).manifests()
            .refresh(CacheManifestsRefreshTest.TAG, origin)
            .toCompletableFuture().get(60, TimeUnit.SECONDS).orElseThrow();
        MatcherAssert.assertThat(
            "the cached manifest is served",
            served, new IsEqual<>(origin)
        );
        MatcherAssert.assertThat(
            "nothing is stored or indexed",
            this.events.size(), new IsEqual<>(0)
        );
    }

    /**
     * Refresh the cached tag.
     *
     * @param allowed Verdict on the manifest the origin names now
     * @return Manifest served
     * @throws Exception On error
     */
    private Manifest refresh(final boolean allowed) throws Exception {
        return this.docker
            .deciding(
                new ManifestCopies(
                    (image, ref, manifest) -> CompletableFuture.completedFuture(allowed)
                )
            )
            .repo(CacheManifestsRefreshTest.IMAGE).manifests()
            .refresh(CacheManifestsRefreshTest.TAG, this.cached)
            .toCompletableFuture().get(60, TimeUnit.SECONDS).orElseThrow();
    }

    /**
     * Manifest the cache holds under the tag.
     *
     * @return Manifest
     */
    private Manifest held() {
        return this.cache.repo(CacheManifestsRefreshTest.IMAGE).manifests()
            .get(CacheManifestsRefreshTest.TAG).join().orElseThrow();
    }

    /**
     * Release date the inspector knows for the tag.
     *
     * @return Date
     */
    private Optional<Instant> tagDate() {
        return this.inspector.releaseDate(
            CacheManifestsRefreshTest.IMAGE, CacheManifestsRefreshTest.TAG.digest()
        ).join();
    }
}
