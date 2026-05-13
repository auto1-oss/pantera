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
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.maven.cooldown.MavenCooldownResponseFactory;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * W6 status-code fidelity (analysis/plan/v1/PLAN.md, RCA-7): the Maven
 * adapter's exception handler must map upstream non-2xx categories to
 * the correct outbound response so the group resolver, index cache,
 * and clients all see authoritative signals.
 *
 * <p>Pre-W6 every upstream 4xx mapped to 404 — poisoning the index
 * cache, masking auth signals, and turning transient 429s into false
 * negatives that mvn / npm tools could not recover from. Pre-W6 5xx
 * mapped to 503 verbatim, which RaceSlice treats as a "winning"
 * response and stopped the group walk.</p>
 *
 * <p>The fidelity matrix tested here:
 * <ul>
 *   <li>404 / 410 → 404 (genuine not-found; group fanout continues)</li>
 *   <li>429 → 429 + Retry-After preserved (M3's gate honours this)</li>
 *   <li>401 / 403 → propagate (authoritative auth signal)</li>
 *   <li>503 with Retry-After → 503 + Retry-After</li>
 *   <li>5xx without Retry-After → 502 (transient, group fanout retries)</li>
 * </ul>
 *
 * @since 2.2.0
 */
final class CachedProxySliceStatusFidelityTest {

    private Queue<ProxyArtifactEvent> events;

    @BeforeEach
    void init() {
        this.events = new LinkedList<>();
        CooldownResponseRegistry.instance()
            .register("maven-proxy", new MavenCooldownResponseFactory());
    }

    @Test
    void upstream404PropagatesAs404() {
        MatcherAssert.assertThat(
            invoke(constantResponse(RsStatus.NOT_FOUND, null)).status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    // NOTE: 410 Gone gets the same notFound mapping as 404 by inspection
    // of mapUpstreamStatus, but pantera-core's RsStatus enum does not
    // include a 410 entry — Maven Central never returns 410, only 404.
    // We rely on a code-level read for that branch rather than a slice-
    // level test; if RsStatus.byCode(410) is ever added, this can become
    // a real test.

    @Test
    void upstream429PreservesRetryAfter() {
        final Response r = invoke(constantResponse(
            RsStatus.TOO_MANY_REQUESTS, "120"
        ));
        MatcherAssert.assertThat(
            "429 propagates as 429",
            r.status(), new IsEqual<>(RsStatus.TOO_MANY_REQUESTS)
        );
        MatcherAssert.assertThat(
            "Retry-After preserved verbatim",
            r.headers().values("Retry-After"),
            new IsEqual<>(List.of("120"))
        );
    }

    @Test
    void upstream401PropagatesVerbatim() {
        MatcherAssert.assertThat(
            invoke(constantResponse(RsStatus.UNAUTHORIZED, null)).status(),
            new IsEqual<>(RsStatus.UNAUTHORIZED)
        );
    }

    @Test
    void upstream403PropagatesVerbatim() {
        MatcherAssert.assertThat(
            invoke(constantResponse(RsStatus.FORBIDDEN, null)).status(),
            new IsEqual<>(RsStatus.FORBIDDEN)
        );
    }

    @Test
    void upstream503WithRetryAfterPropagatesAs503() {
        final Response r = invoke(constantResponse(
            RsStatus.SERVICE_UNAVAILABLE, "60"
        ));
        MatcherAssert.assertThat(
            r.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
        MatcherAssert.assertThat(
            r.headers().values("Retry-After"),
            new IsEqual<>(List.of("60"))
        );
    }

    @Test
    void upstream503WithoutRetryAfterMapsTo502() {
        // Pre-W6 503-no-Retry-After propagated as 503, which RaceSlice
        // treats as a "winning" response and stops the group walk. Now
        // 503-no-Retry-After collapses to 502 so the group fanout
        // retries the next member.
        MatcherAssert.assertThat(
            invoke(constantResponse(RsStatus.SERVICE_UNAVAILABLE, null)).status(),
            new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
    }

    @Test
    void upstream500MapsTo502() {
        MatcherAssert.assertThat(
            invoke(constantResponse(RsStatus.INTERNAL_ERROR, null)).status(),
            new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
    }

    private static Slice constantResponse(final RsStatus status, final String retryAfter) {
        return (line, headers, body) -> {
            final ResponseBuilder rb = ResponseBuilder.from(status);
            if (retryAfter != null) {
                rb.header("Retry-After", retryAfter);
            }
            return CompletableFuture.completedFuture(rb.build());
        };
    }

    private Response invoke(final Slice upstream) {
        final CachedProxySlice slice = new CachedProxySlice(
            upstream,
            (cacheKey, supplier, control) -> supplier.get(),
            Optional.of(this.events), "maven-proxy",
            "https://repo.maven.apache.org/maven2", "maven-proxy",
            NoopCooldownService.INSTANCE, noopInspector(),
            Optional.of(new InMemoryStorage())
        );
        return slice.response(
            new RequestLine(RqMethod.GET, "/com/example/foo/1.0/foo-1.0.jar"),
            Headers.EMPTY, Content.EMPTY
        ).join();
    }

    private static CooldownInspector noopInspector() {
        return new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(
                final String artifact, final String version
            ) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(
                final String artifact, final String version
            ) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
    }
}
