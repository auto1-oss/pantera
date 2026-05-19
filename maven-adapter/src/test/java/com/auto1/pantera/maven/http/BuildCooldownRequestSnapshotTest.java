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
package com.auto1.pantera.maven.http;

import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Phase B — SNAPSHOT timestamp version extraction. The cooldown request must
 * carry the canonical timestamped form for timestamped SNAPSHOTs and fall
 * back to the directory name for releases / non-timestamped SNAPSHOTs.
 *
 * @since 2.2.0
 */
class BuildCooldownRequestSnapshotTest {

    @Test
    void extractsTimestampedVersionFromPrimaryJar() {
        MatcherAssert.assertThat(
            CachedProxySlice.extractSnapshotVersion(
                "org/foo/lib/1.0-SNAPSHOT/lib-1.0-20260519.090000-1.jar"
            ),
            new IsEqual<>(Optional.of("1.0-20260519.090000-1"))
        );
    }

    @Test
    void extractsTimestampedVersionFromClassifierArtifact() {
        MatcherAssert.assertThat(
            CachedProxySlice.extractSnapshotVersion(
                "org/foo/lib/1.0-SNAPSHOT/lib-1.0-20260519.090000-1-sources.jar"
            ),
            new IsEqual<>(Optional.of("1.0-20260519.090000-1"))
        );
    }

    @Test
    void returnsEmptyForNonTimestampedSnapshot() {
        MatcherAssert.assertThat(
            CachedProxySlice.extractSnapshotVersion(
                "org/foo/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar"
            ),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void returnsEmptyForReleaseArtifact() {
        MatcherAssert.assertThat(
            CachedProxySlice.extractSnapshotVersion(
                "org/foo/lib/1.0/lib-1.0.jar"
            ),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void returnsEmptyForPomFile() {
        MatcherAssert.assertThat(
            CachedProxySlice.extractSnapshotVersion(
                "org/foo/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.pom"
            ),
            new IsEqual<>(Optional.empty())
        );
    }
}
