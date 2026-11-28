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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.ExampleStorage;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.scheduling.ArtifactEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The deciding view of {@link CacheDocker} answers what the origin has and
 * copies the manifest into the cache (stores it and queues its event for
 * the search index) only once the answer to the request serves it, or,
 * for a request whose answer decides nothing, once a verdict allows it.
 *
 * @since 2.2.15
 */
final class CacheDockerDecidingTest {

    /**
     * Image name in the origin.
     */
    private static final String IMAGE = "my-alpine";

    /**
     * Tag of the image.
     */
    private static final ManifestReference TAG = ManifestReference.from("1");

    /**
     * Cache storage of the proxy.
     */
    private Storage storage;

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

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.events = new ConcurrentLinkedQueue<>();
        this.inspector = new DockerProxyCooldownInspector();
        this.docker = new CacheDocker(
            new AstoDocker("registry", new ExampleStorage()),
            new AstoDocker("registry", this.storage),
            Optional.of(this.events),
            Optional.of(this.inspector)
        );
    }

    @Test
    @Timeout(120)
    void storesWhatTheVerdictAllowsOnceTheReleaseDateIsKnown() throws Exception {
        final List<Optional<Instant>> dates = new CopyOnWriteArrayList<>();
        final ManifestCopies copies = new ManifestCopies(
            (image, ref, manifest) -> this.inspector.releaseDate(image, ref.digest())
                .thenApply(
                    date -> {
                        dates.add(date);
                        return true;
                    }
                )
        );
        this.docker.deciding(copies).repo(CacheDockerDecidingTest.IMAGE).manifests()
            .get(CacheDockerDecidingTest.TAG).get(60, TimeUnit.SECONDS);
        for (int idx = 0; idx < 400 && !this.cachedWithEvent(); idx += 1) {
            TimeUnit.MILLISECONDS.sleep(25L);
        }
        MatcherAssert.assertThat(
            "the verdict is asked once, when the copy has recorded the release date",
            dates.stream().map(Optional::isPresent).toList(), new IsEqual<>(List.of(true))
        );
        MatcherAssert.assertThat(
            "the manifest it allows is stored under the tag",
            this.cached().manifests().get(CacheDockerDecidingTest.TAG).join().isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "with the event of the tag for the search index",
            this.events.stream().map(ArtifactEvent::artifactVersion).toList(),
            new IsEqual<>(List.of("1"))
        );
    }

    @Test
    @Timeout(120)
    void storesNothingTheVerdictRefuses() throws Exception {
        final List<String> asked = new CopyOnWriteArrayList<>();
        final ManifestCopies copies = new ManifestCopies(
            (image, ref, manifest) -> {
                asked.add(image + ':' + ref.digest());
                return CompletableFuture.completedFuture(false);
            }
        );
        this.docker.deciding(copies).repo(CacheDockerDecidingTest.IMAGE).manifests()
            .get(CacheDockerDecidingTest.TAG).get(60, TimeUnit.SECONDS);
        for (int idx = 0; idx < 400 && asked.isEmpty(); idx += 1) {
            TimeUnit.MILLISECONDS.sleep(25L);
        }
        // A write would come from a callback after the verdict: one that
        // should not exist gets the time to show itself.
        for (int idx = 0; idx < 20; idx += 1) {
            MatcherAssert.assertThat(
                "a manifest the verdict refuses is not stored",
                this.stored(), new IsEqual<>(List.of())
            );
            TimeUnit.MILLISECONDS.sleep(15L);
        }
        MatcherAssert.assertThat(
            "nor queued for the search index",
            this.events.size(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "the verdict was asked about the manifest read",
            asked, new IsEqual<>(List.of("my-alpine:1"))
        );
    }

    @Test
    @Timeout(120)
    void storesTheManifestOnceTheAnswerServesIt() throws Exception {
        final ManifestCopies copies = new ManifestCopies();
        MatcherAssert.assertThat(
            "the deciding view answers the origin's manifest",
            this.docker.deciding(copies).repo(CacheDockerDecidingTest.IMAGE).manifests()
                .get(CacheDockerDecidingTest.TAG).get(60, TimeUnit.SECONDS).isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "and stores nothing while the answer is not known",
            this.stored(), new IsEqual<>(List.of())
        );
        copies.commit();
        for (int idx = 0; idx < 400 && !this.cachedWithEvent(); idx += 1) {
            TimeUnit.MILLISECONDS.sleep(25L);
        }
        MatcherAssert.assertThat(
            "the answer served the version: its manifest is stored under the tag",
            this.cached().manifests().get(CacheDockerDecidingTest.TAG).join().isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "with the event of the tag for the search index",
            this.events.stream().map(ArtifactEvent::artifactVersion).toList(),
            new IsEqual<>(List.of("1"))
        );
    }

    @Test
    @Timeout(120)
    void storesNothingWhenTheAnswerRefusesIt() throws Exception {
        final ManifestCopies copies = new ManifestCopies();
        this.docker.deciding(copies).repo(CacheDockerDecidingTest.IMAGE).manifests()
            .get(CacheDockerDecidingTest.TAG).get(60, TimeUnit.SECONDS);
        copies.discard();
        MatcherAssert.assertThat(
            "a refused version is not stored",
            this.stored(), new IsEqual<>(List.of())
        );
        MatcherAssert.assertThat(
            "and queues no event for the search index",
            this.events.size(), new IsEqual<>(0)
        );
    }

    /**
     * Whether the tag is in the cache and its event queued.
     *
     * @return True once both happened
     */
    private boolean cachedWithEvent() {
        return this.cached().manifests().get(CacheDockerDecidingTest.TAG).join().isPresent()
            && !this.events.isEmpty();
    }

    /**
     * The image in the proxy's cache storage.
     *
     * @return Repo over the storage, never asking the origin
     */
    private Repo cached() {
        return new AstoDocker("registry", this.storage).repo(CacheDockerDecidingTest.IMAGE);
    }

    /**
     * Keys in the cache storage.
     *
     * @return All keys
     */
    private List<String> stored() {
        return this.storage.list(Key.ROOT).join().stream().map(Key::string).toList();
    }
}
