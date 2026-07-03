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
package com.auto1.pantera.files;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.publishdate.PublishDateRegistries;
import com.auto1.pantera.publishdate.PublishDateRegistry;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies that {@code FileProxySlice} wires its admission-gate
 * {@link com.auto1.pantera.publishdate.RegistryBackedInspector} with the
 * slice's configured {@code rtype}, not the bare {@code "file"} literal.
 *
 * <p>{@code FileProxySlice} only serves {@code file-proxy} repositories
 * (per {@code RepositorySlices.java}); the {@code artifact_publish_dates}
 * table keys rows by the suffixed type. Without this wiring, every
 * release-date lookup misses its own data and the admission gate
 * fails-open.
 *
 * @since 2.2.0
 */
final class FileProxySliceInspectorRtypeTest {

    private PublishDateRegistry previousRegistry;

    @BeforeEach
    void capturePreviousRegistry() {
        this.previousRegistry = PublishDateRegistries.instance();
    }

    @AfterEach
    void restorePreviousRegistry() {
        PublishDateRegistries.installDefault(this.previousRegistry);
    }

    @Test
    void fileProxyAdmissionGateInspectorQueriesRegistryWithSliceRtype()
        throws InterruptedException {
        final AtomicReference<String> capturedRepoType = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        PublishDateRegistries.installDefault((rt, name, version) -> {
            capturedRepoType.set(rt);
            latch.countDown();
            return CompletableFuture.completedFuture(Optional.empty());
        });
        final Slice failingRemote = (line, headers, body) ->
            CompletableFuture.failedFuture(new IllegalStateException("upstream"));
        final FileProxySlice slice = new FileProxySlice(
            failingRemote,
            Cache.NOP,
            Optional.empty(),
            "my_file_proxy",
            "file-proxy",
            new InspectingCooldownService(),
            "http://localhost.invalid/",
            Optional.of(new InMemoryStorage())
        );
        slice.response(
            new RequestLine(RqMethod.GET, "/some/artifact.bin"),
            Headers.EMPTY,
            Content.EMPTY
        );
        final boolean called = latch.await(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "inspector must be invoked during admission gate",
            called, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "inspector must query registry with slice's rtype, not bare 'file'",
            capturedRepoType.get(), new IsEqual<>("file-proxy")
        );
    }

    private static final class InspectingCooldownService implements CooldownService {

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            return inspector.releaseDate(request.artifact(), request.version())
                .thenApply(opt -> CooldownResult.allowed());
        }

        @Override
        public CompletableFuture<CooldownResult> evaluateWithKnownDate(
            final CooldownRequest request, final Optional<Instant> knownReleaseDate
        ) {
            return CompletableFuture.completedFuture(CooldownResult.allowed());
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
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }
}
