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
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.cache.ProxyCacheConfig;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies that the maven-proxy / gradle-proxy admission gate threads the
 * slice's configured suffixed {@code rtype} ({@code maven-proxy} or
 * {@code gradle-proxy}) — not a hardcoded literal — into the
 * {@link CooldownRequest} handed to the {@link CooldownService}.
 *
 * <p>Both {@code maven-proxy} and {@code gradle-proxy} repositories route
 * through {@code MavenProxy} → {@code MavenProxySlice} → {@code CachedProxySlice}.
 * The publish-date registry and {@code artifact_publish_dates} table key rows
 * by the suffixed repository type, but {@code MavenProxy} historically
 * constructed its cooldown inspector with the bare literal {@code "maven"}.
 * That mismatch caused release-date lookups to miss their own data,
 * fail-open to "no release date — allowing", and poison the
 * {@code CooldownCache} for every subsequent code path that consulted
 * the same {@code (repoName, artifact, version)} key.
 *
 * <p>v2.2.0 (header-time admission gate): cooldown evaluation moved from
 * a pre-fetch {@code RegistryBackedInspector} probe to a post-headers
 * {@code evaluateWithKnownDate} call that reads {@code Last-Modified}
 * straight from the upstream GET response. {@code RegistryBackedInspector}
 * is no longer wired on the cache-miss happy path. The invariant the
 * test pins is now narrower: the {@link CooldownRequest} fed into
 * {@code evaluateWithKnownDate} MUST carry the slice's suffixed
 * {@code rtype}. {@code JdbcCooldownService.shouldBlockNewArtifact}'s
 * fallback to {@code PublishDateRegistries.instance().publishDate(...)}
 * keys on the same field, so a wrong rtype there would re-introduce the
 * original cache-poisoning failure.
 *
 * <p>The test exercises {@code CachedProxySlice} directly with a mock
 * upstream that returns 200 + {@code Last-Modified}, captures the
 * {@code CooldownRequest} the slice constructs for the admission gate,
 * and asserts {@code request.repoType()} matches the suffixed rtype
 * configured on the slice. Driving the test through HTTP (the prior
 * version's {@code JettyClientSlices} against {@code localhost.invalid})
 * is unnecessary — the rtype invariant lives in
 * {@code buildCooldownRequest}, which is path-agnostic.
 *
 * @since 2.2.0
 */
final class MavenProxySliceInspectorRtypeTest {

    @Test
    void gradleProxyAdmissionGateRequestCarriesSliceRtype()
        throws InterruptedException {
        assertCooldownRequestRtypeMatchesSliceRtype("gradle_proxy", "gradle-proxy");
    }

    @Test
    void mavenProxyAdmissionGateRequestCarriesSliceRtype()
        throws InterruptedException {
        assertCooldownRequestRtypeMatchesSliceRtype("maven_proxy", "maven-proxy");
    }

    private void assertCooldownRequestRtypeMatchesSliceRtype(
        final String rname, final String rtype
    ) throws InterruptedException {
        final AtomicReference<String> capturedRepoType = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        final CapturingCooldownService cooldown = new CapturingCooldownService(
            capturedRepoType, latch
        );
        final byte[] bytes = "test-artifact".getBytes(StandardCharsets.UTF_8);
        final Queue<ProxyArtifactEvent> events = new LinkedList<>();
        final CachedProxySlice slice = new CachedProxySlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT")
                    .body(bytes)
                    .build()
            ),
            (cacheKey, supplier, control) -> supplier.get(),
            Optional.of(events),
            rname,
            "https://repo.maven.apache.org/maven2",
            rtype,
            cooldown,
            new NoopInspector(),
            Optional.of(new InMemoryStorage()),
            ProxyCacheConfig.withCooldown(),
            null
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
            "cooldown must be invoked during admission gate for rtype=" + rtype,
            called, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "CooldownRequest must carry the slice's rtype, not bare 'maven'",
            capturedRepoType.get(), new IsEqual<>(rtype)
        );
    }

    private static final class NoopInspector implements CooldownInspector {

        @Override
        public CompletableFuture<Optional<Instant>> releaseDate(
            final String artifact, final String version
        ) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<List<com.auto1.pantera.cooldown.api.CooldownDependency>> dependencies(
            final String artifact, final String version
        ) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    private static final class CapturingCooldownService implements CooldownService {

        private final AtomicReference<String> captured;
        private final CountDownLatch latch;

        CapturingCooldownService(
            final AtomicReference<String> captured, final CountDownLatch latch
        ) {
            this.captured = captured;
            this.latch = latch;
        }

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }

        @Override
        public CompletableFuture<CooldownResult> evaluateWithKnownDate(
            final CooldownRequest request, final Optional<Instant> knownReleaseDate
        ) {
            this.captured.set(request.repoType());
            this.latch.countDown();
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
