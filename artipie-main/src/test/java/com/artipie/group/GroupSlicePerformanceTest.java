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
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance and stress tests for high-performance GroupSlice.
 */
public final class GroupSlicePerformanceTest {

    @Test
    @Timeout(10)
    void handles250ConcurrentRequests() throws InterruptedException {
        final ExecutorService executor = Executors.newFixedThreadPool(50);
        try {
            final Map<String, Slice> map = new HashMap<>();
            map.put("repo1", new FastSlice(5));
            map.put("repo2", new FastSlice(10));
            map.put("repo3", new FastSlice(15));
            
            final GroupSlice slice = new GroupSlice(
                new MapResolver(map),
                "perf-group",
                List.of("repo1", "repo2", "repo3"),
                8080
            );
            
            final List<CompletableFuture<Void>> futures = IntStream.range(0, 250)
                .mapToObj(i -> CompletableFuture.supplyAsync(
                    () -> slice.response(
                        new RequestLine("GET", "/pkg-" + i),
                        Headers.EMPTY,
                        Content.EMPTY
                    ).join(),
                    executor
                ).thenAccept(resp -> assertEquals(RsStatus.OK, resp.status())))
                .collect(Collectors.toList());
            
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void parallelExecutionFasterThanSequential() {
        final Map<String, Slice> map = new HashMap<>();
        // 3 repos, each takes 50ms
        map.put("repo1", new DelayedSlice(50, RsStatus.NOT_FOUND));
        map.put("repo2", new DelayedSlice(50, RsStatus.NOT_FOUND));
        map.put("repo3", new DelayedSlice(50, RsStatus.OK));
        
        final GroupSlice slice = new GroupSlice(
            new MapResolver(map),
            "parallel-group",
            List.of("repo1", "repo2", "repo3"),
            8080
        );
        
        final long start = System.currentTimeMillis();
        final Response resp = slice.response(
            new RequestLine("GET", "/pkg"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        final long elapsed = System.currentTimeMillis() - start;
        
        assertEquals(RsStatus.OK, resp.status());
        // Should complete in ~50ms (parallel), not 150ms (sequential)
        assertTrue(elapsed < 100, "Expected <100ms parallel execution, got " + elapsed + "ms");
    }

    @Test
    void allResponseBodiesConsumed() {
        final AtomicInteger callCount = new AtomicInteger(0);
        
        final Map<String, Slice> map = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            final int repoNum = i;
            map.put("repo" + i, (line, headers, body) -> {
                callCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    repoNum == 1 
                        ? ResponseBuilder.ok().textBody("success").build()
                        : ResponseBuilder.notFound().build()
                );
            });
        }
        
        final GroupSlice slice = new GroupSlice(
            new MapResolver(map),
            "tracking-group",
            List.of("repo1", "repo2", "repo3", "repo4", "repo5"),
            8080
        );
        
        final Response resp = slice.response(
            new RequestLine("GET", "/pkg"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        
        assertEquals(RsStatus.OK, resp.status(), "Expected OK from first member");
        assertEquals(5, callCount.get(), "Expected all 5 members to be queried in parallel");
    }

    @Test
    void circuitBreakerOpensAfterFailures() {
        final AtomicInteger failureCount = new AtomicInteger(0);
        final Map<String, Slice> map = new HashMap<>();
        map.put("failing", (line, headers, body) -> {
            failureCount.incrementAndGet();
            return CompletableFuture.failedFuture(new RuntimeException("boom"));
        });
        map.put("working", new FastSlice(5));
        
        final GroupSlice slice = new GroupSlice(
            new MapResolver(map),
            "circuit-group",
            List.of("failing", "working"),
            8080
        );
        
        // Make 10 requests
        for (int i = 0; i < 10; i++) {
            slice.response(
                new RequestLine("GET", "/pkg-" + i),
                Headers.EMPTY,
                Content.EMPTY
            ).join();
        }
        
        // Circuit breaker should open after 5 failures
        assertTrue(
            failureCount.get() < 10,
            "Circuit breaker should prevent some requests, got " + failureCount.get() + " failures"
        );
    }

    @Test
    void deduplicatesMembers() {
        final AtomicInteger callCount = new AtomicInteger(0);
        final Map<String, Slice> map = new HashMap<>();
        map.put("repo", (line, headers, body) -> {
            callCount.incrementAndGet();
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        });
        
        // Same repo listed 3 times
        final GroupSlice slice = new GroupSlice(
            new MapResolver(map),
            "dedup-group",
            List.of("repo", "repo", "repo"),
            8080
        );
        
        slice.response(
            new RequestLine("GET", "/pkg"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        
        assertEquals(1, callCount.get(), "Expected repo to be queried only once after deduplication");
    }

    @Test
    @Timeout(5)
    void timeoutPreventsHangingRequests() {
        final Map<String, Slice> map = new HashMap<>();
        map.put("hanging", (line, headers, body) -> new CompletableFuture<>()); // Never completes
        map.put("working", new FastSlice(5));
        
        final GroupSlice slice = new GroupSlice(
            new MapResolver(map),
            "timeout-group",
            List.of("hanging", "working"),
            8080,
            0,
            2 // 2 second timeout
        );
        
        final Response resp = slice.response(
            new RequestLine("GET", "/pkg"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        
        assertEquals(RsStatus.OK, resp.status(), "Should return OK from working member despite hanging member");
    }

    // Helper classes

    private static final class MapResolver implements SliceResolver {
        private final Map<String, Slice> map;
        private MapResolver(Map<String, Slice> map) { this.map = map; }
        @Override
        public Slice slice(Key name, int port, int depth) {
            return map.get(name.string());
        }
    }

    private static final class FastSlice implements Slice {
        private final long delayMs;
        private FastSlice(long delayMs) { this.delayMs = delayMs; }
        @Override
        public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
            return CompletableFuture.supplyAsync(() -> {
                try { Thread.sleep(delayMs); } catch (InterruptedException e) {}
                return ResponseBuilder.ok().textBody("fast").build();
            });
        }
    }

    private static final class DelayedSlice implements Slice {
        private final long delayMs;
        private final RsStatus status;
        private DelayedSlice(long delayMs, RsStatus status) {
            this.delayMs = delayMs;
            this.status = status;
        }
        @Override
        public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
            return CompletableFuture.supplyAsync(() -> {
                try { Thread.sleep(delayMs); } catch (InterruptedException e) {}
                return ResponseBuilder.from(status).build();
            });
        }
    }
}
