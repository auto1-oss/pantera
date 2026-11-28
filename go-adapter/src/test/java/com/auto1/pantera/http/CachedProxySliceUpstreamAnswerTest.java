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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What a Go proxy, wired as the server wires it, answers with what its
 * upstream sent: the headers of a module file it fetched, and the latest
 * signed tree head of a checksum database, which changes with every module
 * the database records and is therefore asked for anew each time.
 *
 * @since 2.2.15
 */
final class CachedProxySliceUpstreamAnswerTest {

    /**
     * Path of a module file that is not a primary archive.
     */
    private static final String MOD = "/example.com/mod/@v/v1.0.0.mod";

    /**
     * Path of the latest signed tree head of a checksum database.
     */
    private static final String LATEST = "/sumdb/sum.golang.org/latest";

    /**
     * Path of a lookup of a checksum database: its answer never changes.
     */
    private static final String LOOKUP = "/sumdb/sum.golang.org/lookup/example.com/mod@v1.0.0";

    /**
     * Thread the upstream answers on, as a real upstream does.
     */
    private ExecutorService answers;

    /**
     * Storage of the proxy.
     */
    private Storage storage;

    @BeforeEach
    void init() {
        this.answers = Executors.newSingleThreadExecutor();
        this.storage = new InMemoryStorage();
    }

    @AfterEach
    void stop() {
        this.answers.shutdownNow();
    }

    @Test
    @Timeout(60)
    void relaysTheHeadersOfAModuleFileTheUpstreamAnswersLater() throws Exception {
        final Upstream upstream = new Upstream(this.answers);
        upstream.serve(CachedProxySliceUpstreamAnswerTest.MOD, "module example.com/mod\n");
        final Response response = this.proxy(upstream).response(
            new RequestLine(RqMethod.GET, CachedProxySliceUpstreamAnswerTest.MOD),
            Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        response.body().asBytesFuture().get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            response.headers().values(Upstream.MARK), new IsEqual<>(List.of("yes"))
        );
    }

    @Test
    @Timeout(60)
    void asksTheUpstreamForTheLatestTreeHeadEveryTime() throws Exception {
        final Upstream upstream = new Upstream(this.answers);
        final Slice proxy = this.proxy(upstream);
        upstream.serve(CachedProxySliceUpstreamAnswerTest.LATEST, "go.sum database tree\n1\n");
        final String first = CachedProxySliceUpstreamAnswerTest.body(
            proxy, CachedProxySliceUpstreamAnswerTest.LATEST
        );
        upstream.serve(CachedProxySliceUpstreamAnswerTest.LATEST, "go.sum database tree\n2\n");
        MatcherAssert.assertThat(
            "the first request gets the tree head the upstream had then",
            first, new IsEqual<>("go.sum database tree\n1\n")
        );
        MatcherAssert.assertThat(
            "a later request gets the tree head the upstream has now",
            CachedProxySliceUpstreamAnswerTest.body(
                proxy, CachedProxySliceUpstreamAnswerTest.LATEST
            ),
            new IsEqual<>("go.sum database tree\n2\n")
        );
        MatcherAssert.assertThat(
            "the tree head is not kept",
            this.storage.list(Key.ROOT).join(), new IsEqual<>(List.of())
        );
    }

    @Test
    @Timeout(60)
    void keepsTheAnswerOfALookupWhichNeverChanges() throws Exception {
        final Upstream upstream = new Upstream(this.answers);
        final Slice proxy = this.proxy(upstream);
        upstream.serve(CachedProxySliceUpstreamAnswerTest.LOOKUP, "123\nexample.com/mod v1.0.0 h1:x\n");
        final String first = CachedProxySliceUpstreamAnswerTest.body(
            proxy, CachedProxySliceUpstreamAnswerTest.LOOKUP
        );
        // The stream-through cache saves once the body was read: wait for
        // the save, do not time it.
        for (
            int idx = 0;
            idx < 400 && this.storage.list(Key.ROOT).join().isEmpty();
            idx += 1
        ) {
            TimeUnit.MILLISECONDS.sleep(25L);
        }
        MatcherAssert.assertThat(
            "a later request gets the same answer",
            CachedProxySliceUpstreamAnswerTest.body(
                proxy, CachedProxySliceUpstreamAnswerTest.LOOKUP
            ),
            new IsEqual<>(first)
        );
        MatcherAssert.assertThat(
            "from the proxy, the upstream was asked once",
            upstream.gets(CachedProxySliceUpstreamAnswerTest.LOOKUP), new IsEqual<>(1)
        );
    }

    /**
     * A Go proxy as the server builds it.
     *
     * @param upstream Upstream
     * @return Proxy
     */
    private Slice proxy(final Slice upstream) {
        return new GoProxySlice(
            upstream, new FromStorageCache(this.storage),
            Optional.of(new ConcurrentLinkedQueue<ProxyArtifactEvent>()),
            Optional.of(this.storage), "go_proxy", "go-proxy", NoopCooldownService.INSTANCE,
            new CooldownInspector() {
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
            }
        );
    }

    /**
     * Ask the proxy as a client and read the whole answer.
     *
     * @param proxy Proxy
     * @param path Request path
     * @return Body of the answer
     * @throws Exception On a failed or late answer
     */
    private static String body(final Slice proxy, final String path) throws Exception {
        return new String(
            proxy.response(new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY)
                .get(30, TimeUnit.SECONDS)
                .body().asBytesFuture().get(30, TimeUnit.SECONDS),
            StandardCharsets.UTF_8
        );
    }

    /**
     * Upstream that answers on its own thread, as an upstream reached over
     * the network does, with a header of its own on every answer.
     *
     * @since 2.2.15
     */
    private static final class Upstream implements Slice {

        /**
         * Header the upstream sets on every answer.
         */
        static final String MARK = "X-Upstream";

        /**
         * Thread the answers come from.
         */
        private final ExecutorService thread;

        /**
         * Bodies by path.
         */
        private final Map<String, byte[]> files;

        /**
         * Number of GET requests by path.
         */
        private final Map<String, AtomicInteger> requests;

        Upstream(final ExecutorService thread) {
            this.thread = thread;
            this.files = new ConcurrentHashMap<>();
            this.requests = new ConcurrentHashMap<>();
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final String path = line.uri().getPath();
            if (line.method() == RqMethod.GET) {
                this.requests.computeIfAbsent(path, ignored -> new AtomicInteger())
                    .incrementAndGet();
            }
            final byte[] found = this.files.get(path);
            return CompletableFuture.supplyAsync(
                () -> {
                    final Response res;
                    if (found == null) {
                        res = ResponseBuilder.notFound().build();
                    } else if (line.method() == RqMethod.HEAD) {
                        res = ResponseBuilder.ok()
                            .header(new Header(Upstream.MARK, "yes"))
                            .build();
                    } else {
                        res = ResponseBuilder.ok()
                            .header(new Header(Upstream.MARK, "yes"))
                            .body(found)
                            .build();
                    }
                    return res;
                },
                this.thread
            );
        }

        void serve(final String path, final String text) {
            this.files.put(path, text.getBytes(StandardCharsets.UTF_8));
        }

        int gets(final String path) {
            return this.requests.getOrDefault(path, new AtomicInteger()).get();
        }
    }
}
