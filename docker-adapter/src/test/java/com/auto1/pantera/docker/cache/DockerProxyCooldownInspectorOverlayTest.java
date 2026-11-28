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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The overlay of a {@link DockerProxyCooldownInspector} serves a request
 * that must leave the repository as it found it (a Troubleshoot replay):
 * cooldown is evaluated with the release dates the repository knows and
 * with those the request reads itself, and what the request reads is
 * neither remembered by the repository's inspector nor persisted.
 *
 * @since 2.2.15
 */
final class DockerProxyCooldownInspectorOverlayTest {

    /**
     * Image name.
     */
    private static final String IMAGE = "library/busybox";

    /**
     * A release date.
     */
    private static final Instant RELEASED = Instant.parse("2020-02-03T04:05:06Z");

    /**
     * Release dates handed to the persisting callback.
     */
    private List<String> persisted;

    /**
     * Inspector of the repository.
     */
    private DockerProxyCooldownInspector repository;

    @BeforeEach
    void setUp() {
        this.persisted = new CopyOnWriteArrayList<>();
        this.repository = new DockerProxyCooldownInspector();
        this.repository.setReleaseDateCallback(
            (artifact, version, release) -> this.persisted.add(artifact + ':' + version)
        );
    }

    @Test
    void readsWhatTheRepositoryKnows() {
        this.repository.recordRelease(
            DockerProxyCooldownInspectorOverlayTest.IMAGE, "1.36",
            DockerProxyCooldownInspectorOverlayTest.RELEASED
        );
        final DockerProxyCooldownInspector overlay = this.repository.overlay();
        MatcherAssert.assertThat(
            "the overlay evaluates with the date the repository knows",
            overlay.releaseDate(DockerProxyCooldownInspectorOverlayTest.IMAGE, "1.36").join(),
            new IsEqual<>(Optional.of(DockerProxyCooldownInspectorOverlayTest.RELEASED))
        );
        MatcherAssert.assertThat(
            "and does not resolve it a second time",
            overlay.known(DockerProxyCooldownInspectorOverlayTest.IMAGE, "1.36"),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "a version nobody saw is unknown to it",
            overlay.known(DockerProxyCooldownInspectorOverlayTest.IMAGE, "1.37"),
            new IsEqual<>(false)
        );
    }

    @Test
    void keepsWhatItLearnsToItself() {
        final DockerProxyCooldownInspector overlay = this.repository.overlay();
        overlay.recordRelease(
            DockerProxyCooldownInspectorOverlayTest.IMAGE, "1.36",
            DockerProxyCooldownInspectorOverlayTest.RELEASED
        );
        overlay.register(
            DockerProxyCooldownInspectorOverlayTest.IMAGE, "sha256:abc",
            Optional.of(DockerProxyCooldownInspectorOverlayTest.RELEASED),
            "admin", "my-proxy", Optional.empty()
        );
        MatcherAssert.assertThat(
            "the overlay evaluates with the date it recorded",
            overlay.releaseDate(DockerProxyCooldownInspectorOverlayTest.IMAGE, "1.36").join(),
            new IsEqual<>(Optional.of(DockerProxyCooldownInspectorOverlayTest.RELEASED))
        );
        MatcherAssert.assertThat(
            "and with the one it registered",
            overlay.releaseDate(
                DockerProxyCooldownInspectorOverlayTest.IMAGE, "sha256:abc"
            ).join(),
            new IsEqual<>(Optional.of(DockerProxyCooldownInspectorOverlayTest.RELEASED))
        );
        MatcherAssert.assertThat(
            "it knows the version it recorded",
            overlay.known(DockerProxyCooldownInspectorOverlayTest.IMAGE, "1.36"),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the repository's inspector has no date for the recorded version",
            this.repository.releaseDate(
                DockerProxyCooldownInspectorOverlayTest.IMAGE, "1.36"
            ).join(),
            new IsEqual<>(Optional.<Instant>empty())
        );
        MatcherAssert.assertThat(
            "nor for the registered one",
            this.repository.releaseDate(
                DockerProxyCooldownInspectorOverlayTest.IMAGE, "sha256:abc"
            ).join(),
            new IsEqual<>(Optional.<Instant>empty())
        );
        MatcherAssert.assertThat(
            "it has not seen the recorded version",
            this.repository.known(DockerProxyCooldownInspectorOverlayTest.IMAGE, "1.36"),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "nor the registered one",
            this.repository.known(DockerProxyCooldownInspectorOverlayTest.IMAGE, "sha256:abc"),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "it has no owner for the registered version",
            this.repository.ownerFor("my-proxy", "sha256:abc"),
            new IsEqual<>(Optional.<String>empty())
        );
        MatcherAssert.assertThat(
            "and no release date was persisted",
            this.persisted, new IsEqual<>(List.of())
        );
    }

    @Test
    void repositoryInspectorStillPersistsWhatItRecords() {
        this.repository.overlay();
        this.repository.recordRelease(
            DockerProxyCooldownInspectorOverlayTest.IMAGE, "1.36",
            DockerProxyCooldownInspectorOverlayTest.RELEASED
        );
        MatcherAssert.assertThat(
            this.persisted,
            new IsEqual<>(List.of(DockerProxyCooldownInspectorOverlayTest.IMAGE + ":1.36"))
        );
    }
}
