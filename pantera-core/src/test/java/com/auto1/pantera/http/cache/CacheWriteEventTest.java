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
package com.auto1.pantera.http.cache;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CacheWriteEvent} — Phase 11 ownership flag.
 *
 * @since 2.2.0
 */
final class CacheWriteEventTest {

    @Test
    void legacyFiveArgCtorDefaultsToCallerOwnsSnapshotTrue() {
        // ProxyCacheWriter / BaseCachedProxySlice still construct with 5
        // args; that path MUST default to caller-owned (true) because the
        // writer deletes the temp file right after fireOnWrite returns.
        final CacheWriteEvent event = new CacheWriteEvent(
            "maven_proxy",
            "com/example/foo/1.0/foo-1.0.pom",
            Paths.get("/tmp/pantera-proxy-XYZ.tmp"),
            1234L,
            Instant.now()
        );
        MatcherAssert.assertThat(
            "5-arg ctor must default callerOwnsSnapshot to true",
            event.callerOwnsSnapshot(),
            new IsEqual<>(true)
        );
    }

    @Test
    void sixArgCtorPreservesCallerOwnsSnapshotFalse() {
        // NpmCacheWriteBridge zero-copy passthrough path constructs with 6
        // args and callerOwnsSnapshot=false; the dispatcher must NOT delete
        // the storage-owned path.
        final Path storagePath = Paths.get("/var/lib/pantera/npm_proxy/express/-/express-4.21.0.tgz");
        final CacheWriteEvent event = new CacheWriteEvent(
            "npm_proxy",
            "express/-/express-4.21.0.tgz",
            storagePath,
            58_000L,
            Instant.now(),
            false
        );
        MatcherAssert.assertThat(
            "6-arg ctor preserves callerOwnsSnapshot=false",
            event.callerOwnsSnapshot(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(event.bytesOnDisk(), new IsEqual<>(storagePath));
    }

    @Test
    void sixArgCtorPreservesCallerOwnsSnapshotTrue() {
        final CacheWriteEvent event = new CacheWriteEvent(
            "maven_proxy",
            "com/example/bar/1.0/bar-1.0.jar",
            Paths.get("/tmp/pantera-proxy-ABC.tmp"),
            5678L,
            Instant.now(),
            true
        );
        MatcherAssert.assertThat(
            event.callerOwnsSnapshot(),
            new IsEqual<>(true)
        );
    }
}
