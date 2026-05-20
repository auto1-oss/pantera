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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.client.auth.Authenticator;
import com.auto1.pantera.http.client.jetty.JettyClientSlices;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.publishdate.PublishDateRegistries;
import com.auto1.pantera.publishdate.PublishDateRegistry;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies that {@code MavenProxySlice} wires its admission-gate
 * {@link com.auto1.pantera.publishdate.RegistryBackedInspector} with the
 * slice's configured {@code rtype}, not a hardcoded literal.
 *
 * <p>Both {@code maven-proxy} and {@code gradle-proxy} repositories route
 * through {@code MavenProxy} → {@code MavenProxySlice}. The publish-date
 * registry and {@code artifact_publish_dates} table key rows by the
 * suffixed repository type ({@code "maven-proxy"} or {@code "gradle-proxy"}),
 * but the slice historically constructed its inspector with the bare
 * literal {@code "maven"}. That mismatch caused every admission-gate
 * release-date lookup to miss its own data, fail-open to "no release date
 * — allowing", and poison the {@code CooldownCache} for every subsequent
 * code path that consulted the same {@code (repoName, artifact, version)}
 * key — including the metadata filter that had the right data.
 *
 * <p>This test installs a tracking {@link PublishDateRegistry} as the
 * process-wide default, constructs a {@code MavenProxySlice} configured
 * for {@code rtype="gradle-proxy"}, issues a cache-miss primary-artifact
 * request, and asserts the tracker observed a lookup keyed by
 * {@code "gradle-proxy"} — never bare {@code "maven"}.
 *
 * @since 2.2.0
 */
final class MavenProxySliceInspectorRtypeTest {

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
    void gradleProxyAdmissionGateInspectorQueriesRegistryWithSliceRtype()
        throws InterruptedException {
        assertInspectorRtypeMatchesSliceRtype("gradle_proxy", "gradle-proxy");
    }

    @Test
    void mavenProxyAdmissionGateInspectorQueriesRegistryWithSliceRtype()
        throws InterruptedException {
        assertInspectorRtypeMatchesSliceRtype("maven_proxy", "maven-proxy");
    }

    private void assertInspectorRtypeMatchesSliceRtype(
        final String rname, final String rtype
    ) throws InterruptedException {
        final AtomicReference<String> capturedRepoType = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        PublishDateRegistries.installDefault((rt, name, version) -> {
            capturedRepoType.set(rt);
            latch.countDown();
            return CompletableFuture.completedFuture(Optional.empty());
        });
        final MavenProxySlice slice = new MavenProxySlice(
            new JettyClientSlices(),
            URI.create("http://localhost.invalid/"),
            Authenticator.ANONYMOUS,
            Cache.NOP,
            Optional.empty(),
            rname,
            rtype,
            new InspectingCooldownService(),
            Optional.of(new InMemoryStorage())
        );
        slice.response(
            new RequestLine(
                RqMethod.GET,
                "/com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar"
            ),
            Headers.EMPTY,
            Content.EMPTY
        );
        final boolean called = latch.await(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "inspector must be invoked during admission gate for rtype=" + rtype,
            called, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "inspector must query registry with slice's rtype, not bare 'maven'",
            capturedRepoType.get(), new IsEqual<>(rtype)
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
