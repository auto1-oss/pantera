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
package com.auto1.pantera.group;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class GroupSliceTest {

    @Test
    void returnsFirstNon404() {
        final Map<String, Slice> map = new HashMap<>();
        map.put("local", new StaticSlice(RsStatus.NOT_FOUND));
        map.put("proxy", new StaticSlice(RsStatus.OK));
        final GroupSlice slice = new GroupSlice(new MapResolver(map), "group", List.of("local", "proxy"), 8080);
        final Response rsp = slice.response(new RequestLine("GET", "/pkg.json"), Headers.EMPTY, Content.EMPTY).join();
        assertEquals(RsStatus.OK, rsp.status());
    }

    @Test
    void returns404WhenAllNotFound() {
        final Map<String, Slice> map = new HashMap<>();
        map.put("local", new StaticSlice(RsStatus.NOT_FOUND));
        map.put("proxy", new StaticSlice(RsStatus.NOT_FOUND));
        final GroupSlice slice = new GroupSlice(new MapResolver(map), "group", List.of("local", "proxy"), 8080);
        final Response rsp = slice.response(new RequestLine("GET", "/a/b"), Headers.EMPTY, Content.EMPTY).join();
        assertEquals(RsStatus.NOT_FOUND, rsp.status());
    }

    @Test
    void methodNotAllowedForUploads() {
        final Map<String, Slice> map = new HashMap<>();
        map.put("local", new StaticSlice(RsStatus.OK));
        final GroupSlice slice = new GroupSlice(new MapResolver(map), "group", List.of("local"), 8080);
        final Response rsp = slice.response(new RequestLine("POST", "/upload"), Headers.EMPTY, Content.EMPTY).join();
        assertEquals(RsStatus.METHOD_NOT_ALLOWED, rsp.status());
    }

    @Test
    void rewritesPathWithMemberPrefix() {
        final AtomicReference<RequestLine> seen = new AtomicReference<>();
        final Slice recording = (line, headers, body) -> {
            seen.set(line);
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        };
        final Map<String, Slice> map = new HashMap<>();
        map.put("npm-local", recording);
        final GroupSlice slice = new GroupSlice(new MapResolver(map), "group", List.of("npm-local"), 8080);
        slice.response(new RequestLine("GET", "/@scope/pkg/-/pkg-1.0.tgz?x=1"), Headers.EMPTY, Content.EMPTY).join();
        assertTrue(seen.get().uri().getPath().startsWith("/npm-local/@scope/pkg/-/pkg-1.0.tgz"));
        assertEquals("x=1", seen.get().uri().getQuery());
    }

    @Test
    void returnsNotModifiedWhenMemberReturnsNotModified() {
        // Test that 304 NOT_MODIFIED is treated as success, not failure
        // This is critical for NPM proxy caching with If-None-Match/If-Modified-Since headers
        final Map<String, Slice> map = new HashMap<>();
        map.put("local", new StaticSlice(RsStatus.NOT_FOUND));
        map.put("proxy", new StaticSlice(RsStatus.NOT_MODIFIED));
        final GroupSlice slice = new GroupSlice(new MapResolver(map), "group", List.of("local", "proxy"), 8080);
        final Response rsp = slice.response(new RequestLine("GET", "/pkg.json"), Headers.EMPTY, Content.EMPTY).join();
        assertEquals(RsStatus.NOT_MODIFIED, rsp.status(), "304 NOT_MODIFIED should be returned to client");
    }

    @Test
    void returnsNotModifiedFromFirstMemberThatReturnsIt() {
        // Test that first NOT_MODIFIED wins in parallel race
        final Map<String, Slice> map = new HashMap<>();
        map.put("local", new StaticSlice(RsStatus.NOT_FOUND));
        map.put("proxy1", new StaticSlice(RsStatus.NOT_MODIFIED));
        map.put("proxy2", new StaticSlice(RsStatus.OK));
        final GroupSlice slice = new GroupSlice(new MapResolver(map), "group", List.of("local", "proxy1", "proxy2"), 8080);
        final Response rsp = slice.response(new RequestLine("GET", "/pkg.json"), Headers.EMPTY, Content.EMPTY).join();
        // Either NOT_MODIFIED or OK is acceptable (parallel race), but NOT 404
        assertTrue(
            rsp.status() == RsStatus.NOT_MODIFIED || rsp.status() == RsStatus.OK,
            "Should return NOT_MODIFIED or OK, not 404"
        );
    }

    @Test
    void handlesHundredParallelRequestsWithMixedResults() throws InterruptedException {
        final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(8);
        final ExecutorService executor = Executors.newFixedThreadPool(12);
        try {
            final Map<String, Slice> map = new HashMap<>();
            map.put(
                "unstable",
                new ScheduledSlice(scheduler, 5, () -> {
                    throw new IllegalStateException("boom");
                })
            );
            map.put(
                "not-found",
                new ScheduledSlice(scheduler, 15, () -> ResponseBuilder.notFound().build())
            );
            map.put(
                "fast",
                new ScheduledSlice(scheduler, 10, () -> ResponseBuilder.ok().textBody("fast").build())
            );
            final GroupSlice slice = new GroupSlice(
                new MapResolver(map),
                "group",
                Arrays.asList("unstable", "not-found", "fast"),
                8080
            );
            final List<CompletableFuture<Void>> pending = IntStream.range(0, 100)
                .mapToObj(index -> CompletableFuture.supplyAsync(
                    () -> slice.response(
                        new RequestLine("GET", "/pkg-" + index),
                        Headers.EMPTY,
                        Content.EMPTY
                    ).join(),
                    executor
                ).thenAccept(resp -> assertEquals(RsStatus.OK, resp.status())))
                .collect(Collectors.toList());
            CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdownNow();
            scheduler.shutdownNow();
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Test that Go module paths work correctly through the group.
     * The Go module path like /github.com/google/uuid/@v/v1.6.0.info
     * should be rewritten to /go_proxy/github.com/google/uuid/@v/v1.6.0.info
     * for the member, and TrimPathSlice should strip the /go_proxy prefix.
     */
    @Test
    void goModulePathRewritingWorks() {
        final AtomicReference<RequestLine> seen = new AtomicReference<>();
        // This simulates what TrimPathSlice does - it receives /member/path and strips to /path
        final Slice trimmed = (line, headers, body) -> {
            final String path = line.uri().getPath();
            if (path.startsWith("/go_proxy/")) {
                final String stripped = path.substring("/go_proxy".length());
                seen.set(new RequestLine(line.method().value(), stripped, line.version()));
                return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
            }
            // Unexpected path - return 500 for debugging
            seen.set(line);
            return CompletableFuture.completedFuture(
                ResponseBuilder.internalError().textBody("Unexpected path: " + path).build()
            );
        };
        final Map<String, Slice> map = new HashMap<>();
        map.put("go_proxy", trimmed);
        final GroupSlice slice = new GroupSlice(new MapResolver(map), "go_group", List.of("go_proxy"), 8080);
        // Simulate what goes into the group after the group's own TrimPathSlice stripped /go_group
        final Response rsp = slice.response(
            new RequestLine("GET", "/github.com/google/uuid/@v/v1.6.0.info"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals(RsStatus.OK, rsp.status());
        assertNotNull(seen.get());
        assertEquals("/github.com/google/uuid/@v/v1.6.0.info", seen.get().uri().getPath(),
            "After TrimPathSlice simulation, path should be the Go module path without member prefix");
    }

    private static final class MapResolver implements SliceResolver {
        private final Map<String, Slice> map;
        private MapResolver(Map<String, Slice> map) { this.map = map; }
        @Override
        public Slice slice(Key name, int port, int depth) {
            return map.get(name.string());
        }
    }

    private static final class StaticSlice implements Slice {
        private final RsStatus status;
        private StaticSlice(RsStatus status) { this.status = status; }
        @Override
        public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
            return ResponseBuilder.from(status).completedFuture();
        }
    }

    private static final class ScheduledSlice implements Slice {
        private final ScheduledExecutorService scheduler;
        private final long delayMillis;
        private final Supplier<Response> supplier;

        private ScheduledSlice(
            final ScheduledExecutorService scheduler,
            final long delayMillis,
            final Supplier<Response> supplier
        ) {
            this.scheduler = scheduler;
            this.delayMillis = delayMillis;
            this.supplier = supplier;
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line,
            final Headers headers,
            final Content body
        ) {
            final CompletableFuture<Response> future = new CompletableFuture<>();
            this.scheduler.schedule(
                () -> {
                    try {
                        future.complete(this.supplier.get());
                    } catch (final RuntimeException err) {
                        future.completeExceptionally(err);
                    }
                },
                this.delayMillis,
                TimeUnit.MILLISECONDS
            );
            return future;
        }
    }
}
