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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.misc.OfficialImageName;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link DockerLinkedDigests}: an unblocked tag releases exactly the
 * digests reachable from its cached manifest.
 *
 * @since 2.2.9
 */
final class DockerLinkedDigestsTest {

    /**
     * First child manifest digest.
     */
    private static final String AMD64 =
        "sha256:1111111111111111111111111111111111111111111111111111111111111111";

    /**
     * Second child manifest digest.
     */
    private static final String ARM64 =
        "sha256:2222222222222222222222222222222222222222222222222222222222222222";

    /**
     * Cache storage view.
     */
    private Docker cache;

    /**
     * Digest of the cached index.
     */
    private String index;

    @BeforeEach
    void setUp() {
        this.cache = new AstoDocker("my-docker", new InMemoryStorage());
        // The client pulled "my-docker/nginx": the cache holds the index
        // under the trimmed client spelling.
        final Manifest stored = this.cache.repo("nginx").manifests().putUnchecked(
            ManifestReference.fromTag("1.27"),
            new Content.From(
                String.format(
                    "{\"schemaVersion\":2,"
                        + "\"mediaType\":\"application/vnd.oci.image.index.v1+json\","
                        + "\"manifests\":["
                        + "{\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                        + "\"digest\":\"%s\",\"size\":10},"
                        + "{\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                        + "\"digest\":\"%s\",\"size\":10}]}",
                    AMD64, ARM64
                ).getBytes(StandardCharsets.UTF_8)
            )
        ).join();
        this.index = stored.digest().string();
    }

    @Test
    void tagReleasesItsIndexAndChildren() {
        MatcherAssert.assertThat(
            new DockerLinkedDigests(this.cache, new OfficialImageName(true))
                .linked("library/nginx", "1.27").join(),
            new IsEqual<>(List.of(this.index, AMD64, ARM64))
        );
    }

    @Test
    void indexDigestReleasesItsChildren() {
        MatcherAssert.assertThat(
            new DockerLinkedDigests(this.cache, new OfficialImageName(true))
                .linked("library/nginx", this.index).join(),
            new IsEqual<>(List.of(AMD64, ARM64))
        );
    }

    @Test
    void uncachedTagReleasesNothing() {
        MatcherAssert.assertThat(
            new DockerLinkedDigests(this.cache, new OfficialImageName(true))
                .linked("library/nginx", "1.28").join(),
            new IsEqual<>(List.of())
        );
    }
}
