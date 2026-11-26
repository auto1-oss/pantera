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

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.Header;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * The release date cooldown decides with. An upstream that is not Maven
 * Central may report the day it cached a file as its date (repositories
 * run on Artifactory do), which made cooldown read old releases as new.
 * For such an upstream the date comes from Maven Central when the artifact
 * exists there, and from the upstream's own header only as a fallback.
 *
 * @since 2.2.15
 */
final class CachedProxySliceReleaseDateTest {

    /**
     * A primary artifact.
     */
    private static final String PATH = "/org/jline/jline/3.30.9/jline-3.30.9.jar";

    /**
     * The day the artifact was released.
     */
    private static final Instant RELEASED = Instant.parse("2024-03-01T10:00:00Z");

    /**
     * What the upstream reports: the day it cached the file.
     */
    private static final Headers CACHED_TODAY = Headers.from(
        List.of(new Header("Last-Modified", "Thu, 26 Nov 2026 09:00:00 GMT"))
    );

    @Test
    void upstreamThatIsNotCentralGetsItsDateFromCentral() throws Exception {
        final Recorded recorded = new Recorded(Optional.of(CachedProxySliceReleaseDateTest.RELEASED));
        recorded.slice("https://groovy.jfrog.io/artifactory/plugins-release").cooldownAtHeaders(
            Headers.EMPTY, CachedProxySliceReleaseDateTest.PATH,
            CachedProxySliceReleaseDateTest.CACHED_TODAY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "cooldown decides with the release date, not the day the upstream cached the file",
            recorded.date.get(),
            new IsEqual<>(Optional.of(CachedProxySliceReleaseDateTest.RELEASED))
        );
        MatcherAssert.assertThat(
            "asked for the artifact and version of the request",
            recorded.asked.get(), new IsEqual<>("org.jline.jline 3.30.9")
        );
    }

    @Test
    void artifactThatCentralDoesNotHaveKeepsTheUpstreamDate() throws Exception {
        final Recorded recorded = new Recorded(Optional.empty());
        recorded.slice("https://groovy.jfrog.io/artifactory/plugins-release").cooldownAtHeaders(
            Headers.EMPTY, CachedProxySliceReleaseDateTest.PATH,
            CachedProxySliceReleaseDateTest.CACHED_TODAY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            recorded.date.get(),
            new IsEqual<>(Optional.of(Instant.parse("2026-11-26T09:00:00Z")))
        );
    }

    @Test
    void lookupThatFailsKeepsTheUpstreamDate() throws Exception {
        final Recorded recorded = new Recorded(null);
        recorded.slice("https://groovy.jfrog.io/artifactory/plugins-release").cooldownAtHeaders(
            Headers.EMPTY, CachedProxySliceReleaseDateTest.PATH,
            CachedProxySliceReleaseDateTest.CACHED_TODAY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            recorded.date.get(),
            new IsEqual<>(Optional.of(Instant.parse("2026-11-26T09:00:00Z")))
        );
    }

    @Test
    void centralItselfIsNotAskedTwice() throws Exception {
        for (final String central : List.of(
            "https://repo1.maven.org/maven2", "https://repo.maven.apache.org/maven2/"
        )) {
            final Recorded recorded =
                new Recorded(Optional.of(CachedProxySliceReleaseDateTest.RELEASED));
            recorded.slice(central).cooldownAtHeaders(
                Headers.EMPTY, CachedProxySliceReleaseDateTest.PATH,
                CachedProxySliceReleaseDateTest.CACHED_TODAY
            ).get(30, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                central + ": its own header is the release date",
                recorded.date.get(),
                new IsEqual<>(Optional.of(Instant.parse("2026-11-26T09:00:00Z")))
            );
            MatcherAssert.assertThat(
                central + ": no second request for the date",
                recorded.lookups.get(), new IsEqual<>(0)
            );
        }
    }

    @Test
    void noDateIsLookedUpWhereCooldownDoesNotApply() throws Exception {
        final Recorded recorded = new Recorded(Optional.of(CachedProxySliceReleaseDateTest.RELEASED));
        recorded.applies = false;
        recorded.slice("https://groovy.jfrog.io/artifactory/plugins-release").cooldownAtHeaders(
            Headers.EMPTY, CachedProxySliceReleaseDateTest.PATH,
            CachedProxySliceReleaseDateTest.CACHED_TODAY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(recorded.lookups.get(), new IsEqual<>(0));
    }

    @Test
    void dateKeptForTheArtifactIsTheOneCooldownDecidedWith() throws Exception {
        // What is stored with the artifact becomes its date for good (the
        // publish-date table never changes a row): it must be the release
        // date, not the day the upstream cached the file.
        MatcherAssert.assertThat(
            CachedProxySliceReleaseDateTest.storedDate(
                "https://groovy.jfrog.io/artifactory/plugins-release",
                new Recorded(Optional.of(CachedProxySliceReleaseDateTest.RELEASED))
            ),
            new IsEqual<>(Optional.of(CachedProxySliceReleaseDateTest.RELEASED.toEpochMilli()))
        );
    }

    @Test
    void upstreamDateIsKeptOnlyWhenCentralWasAskedAndDoesNotHaveTheArtifact() throws Exception {
        final String other = "https://groovy.jfrog.io/artifactory/plugins-release";
        final long reported = Instant.parse("2026-11-26T09:00:00Z").toEpochMilli();
        MatcherAssert.assertThat(
            "Central does not have it: the upstream's date is the fallback",
            CachedProxySliceReleaseDateTest.storedDate(other, new Recorded(Optional.empty())),
            new IsEqual<>(Optional.of(reported))
        );
        MatcherAssert.assertThat(
            "the lookup failed: nobody knows, and a guess is not kept",
            CachedProxySliceReleaseDateTest.storedDate(other, new Recorded(null)),
            new IsEqual<>(Optional.empty())
        );
        final Recorded off = new Recorded(Optional.of(CachedProxySliceReleaseDateTest.RELEASED));
        off.applies = false;
        MatcherAssert.assertThat(
            "cooldown does not apply, so Central was not asked: no date is kept",
            CachedProxySliceReleaseDateTest.storedDate(other, off),
            new IsEqual<>(Optional.empty())
        );
        MatcherAssert.assertThat(
            "Maven Central's own date is kept as before",
            CachedProxySliceReleaseDateTest.storedDate(
                "https://repo1.maven.org/maven2", new Recorded(Optional.empty())
            ),
            new IsEqual<>(Optional.of(reported))
        );
    }

    /**
     * Fetch the artifact through a proxy of an upstream that reports
     * today as its date, and return the release date of the event the
     * proxy wrote for the search index and the publish-date table.
     */
    private static Optional<Long> storedDate(final String upstream, final Recorded recorded)
        throws Exception {
        final Storage storage = new InMemoryStorage();
        final java.util.Queue<com.auto1.pantera.scheduling.ProxyArtifactEvent> events =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
        final CachedProxySlice slice = new CachedProxySlice(
            (line, headers, body) -> {
                final com.auto1.pantera.http.Response res;
                if (line.uri().getPath().equals(CachedProxySliceReleaseDateTest.PATH)) {
                    res = ResponseBuilder.ok()
                        .headers(CachedProxySliceReleaseDateTest.CACHED_TODAY)
                        .body("jar bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .build();
                } else {
                    res = ResponseBuilder.notFound().build();
                }
                return CompletableFuture.completedFuture(res);
            },
            new FromStorageCache(storage), Optional.of(events), "plugins", upstream,
            "maven-proxy", recorded, recorded, Optional.of(storage)
        );
        slice.response(
            new com.auto1.pantera.http.rq.RequestLine(
                com.auto1.pantera.http.rq.RqMethod.GET, CachedProxySliceReleaseDateTest.PATH
            ),
            Headers.EMPTY, com.auto1.pantera.asto.Content.EMPTY
        ).get(60, TimeUnit.SECONDS).body().asBytesFuture().get(60, TimeUnit.SECONDS);
        // The event follows the commit: wait for it, do not time it.
        for (int idx = 0; idx < 400 && events.isEmpty(); idx += 1) {
            TimeUnit.MILLISECONDS.sleep(25L);
        }
        MatcherAssert.assertThat("an event was written", events.size(), new IsEqual<>(1));
        return events.peek().releaseMillis();
    }

    /**
     * A proxy slice with a cooldown service that records the date it is
     * given, and a date lookup that records what it is asked.
     */
    private static final class Recorded implements CooldownService, CooldownInspector {

        /**
         * The date cooldown was given.
         */
        private final AtomicReference<Optional<Instant>> date = new AtomicReference<>();

        /**
         * Artifact and version the lookup was asked for.
         */
        private final AtomicReference<String> asked = new AtomicReference<>("");

        /**
         * Lookups made.
         */
        private final AtomicInteger lookups = new AtomicInteger();

        /**
         * What the lookup answers; null to fail.
         */
        private final Optional<Instant> central;

        /**
         * Whether cooldown applies to the request.
         */
        private volatile boolean applies = true;

        Recorded(final Optional<Instant> central) {
            this.central = central;
        }

        CachedProxySlice slice(final String upstream) {
            final Storage storage = new InMemoryStorage();
            return new CachedProxySlice(
                (line, headers, body) ->
                    CompletableFuture.completedFuture(ResponseBuilder.notFound().build()),
                new FromStorageCache(storage), Optional.empty(), "plugins", upstream,
                "maven-proxy", this, this, Optional.of(storage)
            );
        }

        @Override
        public boolean applies(final CooldownRequest request) {
            return this.applies;
        }

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }

        @Override
        public CompletableFuture<CooldownResult> evaluateWithKnownDate(
            final CooldownRequest request, final Optional<Instant> known
        ) {
            this.date.set(known);
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }

        @Override
        public CompletableFuture<Void> unblock(
            final String type, final String repo, final String artifact,
            final String version, final String actor
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unblockAll(
            final String type, final String repo, final String actor
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<CooldownBlock>> activeBlocks(
            final String type, final String repo
        ) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<Optional<Instant>> releaseDate(
            final String artifact, final String version
        ) {
            this.lookups.incrementAndGet();
            this.asked.set(artifact + ' ' + version);
            final CompletableFuture<Optional<Instant>> res;
            if (this.central == null) {
                res = CompletableFuture.failedFuture(new IllegalStateException("no network"));
            } else {
                res = CompletableFuture.completedFuture(this.central);
            }
            return res;
        }

        @Override
        public CompletableFuture<List<CooldownDependency>> dependencies(
            final String artifact, final String version
        ) {
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
