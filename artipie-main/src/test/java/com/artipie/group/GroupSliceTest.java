/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.group;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.RsStatus;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
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
    void cancelsSlowerMembersAfterSuccess() {
        final AtomicReference<CompletableFuture<Response>> slowRef = new AtomicReference<>();
        final Slice fast = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok().textBody("fast").build()
        );
        final Slice slow = (line, headers, body) -> {
            final CompletableFuture<Response> future = new CompletableFuture<>();
            slowRef.set(future);
            return future;
        };
        final Map<String, Slice> map = new HashMap<>();
        map.put("fast", fast);
        map.put("slow", slow);
        final GroupSlice slice = new GroupSlice(new MapResolver(map), "group", List.of("fast", "slow"), 8080);
        final Response response = slice.response(new RequestLine("GET", "/pkg.bin"), Headers.EMPTY, Content.EMPTY).join();
        assertEquals(RsStatus.OK, response.status());
        final CompletableFuture<Response> slowFuture = slowRef.get();
        assertNotNull(slowFuture, "Expected slow member to be invoked");
        assertTrue(slowFuture.isCancelled(), "Slow member future must be cancelled after winner is chosen");
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

    private static final class MapResolver implements SliceResolver {
        private final Map<String, Slice> map;
        private MapResolver(Map<String, Slice> map) { this.map = map; }
        @Override
        public Slice slice(Key name, int port) {
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
