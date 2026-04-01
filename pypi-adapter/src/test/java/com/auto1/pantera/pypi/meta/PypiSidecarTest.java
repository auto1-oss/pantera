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
package com.auto1.pantera.pypi.meta;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

/**
 * Tests for {@link PypiSidecar}.
 *
 * @since 2.1.0
 */
class PypiSidecarTest {

    /**
     * In-memory storage instance reused by each test.
     */
    private Storage storage;

    /**
     * A representative artifact key used across multiple tests.
     */
    private static final Key ARTIFACT_KEY =
        new Key.From("requests", "2.28.0", "requests-2.28.0.tar.gz");

    /**
     * Upload timestamp used across tests (fixed for determinism).
     */
    private static final Instant UPLOAD_TIME = Instant.parse("2026-04-01T10:30:00Z");

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
    }

    @Test
    void writesAndReadsSidecar() {
        PypiSidecar.write(this.storage, ARTIFACT_KEY, ">=3.8", UPLOAD_TIME).join();

        final Optional<PypiSidecar.Meta> result =
            PypiSidecar.read(this.storage, ARTIFACT_KEY).join();

        MatcherAssert.assertThat(
            "Sidecar should be present after write",
            result.isPresent(),
            new IsEqual<>(true)
        );

        final PypiSidecar.Meta meta = result.get();

        MatcherAssert.assertThat(
            "requires-python should match",
            meta.requiresPython(),
            new IsEqual<>(">=3.8")
        );
        MatcherAssert.assertThat(
            "upload-time should match",
            meta.uploadTime(),
            new IsEqual<>(UPLOAD_TIME)
        );
        MatcherAssert.assertThat(
            "yanked should be false initially",
            meta.yanked(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "yanked-reason should be empty",
            meta.yankedReason(),
            new IsEqual<>(Optional.empty())
        );
        MatcherAssert.assertThat(
            "dist-info-metadata should be empty",
            meta.distInfoMetadata(),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void returnsEmptyWhenNoSidecar() {
        final Optional<PypiSidecar.Meta> result =
            PypiSidecar.read(this.storage, ARTIFACT_KEY).join();

        MatcherAssert.assertThat(
            "read should return empty when no sidecar exists",
            result,
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void yanksAndUnyanks() {
        PypiSidecar.write(this.storage, ARTIFACT_KEY, ">=3.9", UPLOAD_TIME).join();

        // Yank with a reason
        PypiSidecar.yank(this.storage, ARTIFACT_KEY, "Critical security vulnerability").join();

        final PypiSidecar.Meta yanked =
            PypiSidecar.read(this.storage, ARTIFACT_KEY).join()
                .orElseThrow(() -> new AssertionError("Sidecar missing after yank"));

        MatcherAssert.assertThat(
            "yanked should be true after yank()",
            yanked.yanked(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "yanked-reason should be set",
            yanked.yankedReason(),
            new IsEqual<>(Optional.of("Critical security vulnerability"))
        );

        // Unyank
        PypiSidecar.unyank(this.storage, ARTIFACT_KEY).join();

        final PypiSidecar.Meta unyanked =
            PypiSidecar.read(this.storage, ARTIFACT_KEY).join()
                .orElseThrow(() -> new AssertionError("Sidecar missing after unyank"));

        MatcherAssert.assertThat(
            "yanked should be false after unyank()",
            unyanked.yanked(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "yanked-reason should be cleared after unyank()",
            unyanked.yankedReason(),
            new IsEqual<>(Optional.empty())
        );
        MatcherAssert.assertThat(
            "requires-python should be preserved through yank/unyank cycle",
            unyanked.requiresPython(),
            new IsEqual<>(">=3.9")
        );
        MatcherAssert.assertThat(
            "upload-time should be preserved through yank/unyank cycle",
            unyanked.uploadTime(),
            new IsEqual<>(UPLOAD_TIME)
        );
    }
}
