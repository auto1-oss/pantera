/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.group;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.cache.GroupSettings;
import com.artipie.cache.MetadataMerger;
import com.artipie.cache.UnifiedGroupCache;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.RsStatus;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
     * Test that metadata requests are routed through UnifiedGroupCache when enabled.
     */
    @Test
    void metadataRequestsUseCacheWhenEnabled() {
        final Map<String, Slice> map = new HashMap<>();
        // Member 1: returns metadata for "test-package"
        map.put("local", (line, headers, body) -> {
            final String path = line.uri().getPath();
            if (path.contains("test-package")) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .textBody("{\"name\":\"test-package\",\"version\":\"1.0.0\"}")
                        .build()
                );
            }
            return ResponseBuilder.notFound().completedFuture();
        });
        // Member 2: returns different metadata for "test-package"
        map.put("proxy", (line, headers, body) -> {
            final String path = line.uri().getPath();
            if (path.contains("test-package")) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .textBody("{\"name\":\"test-package\",\"version\":\"2.0.0\"}")
                        .build()
                );
            }
            return ResponseBuilder.notFound().completedFuture();
        });

        // Simple merger that concatenates all responses
        final MetadataMerger merger = responses -> {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (byte[] data : responses.values()) {
                if (!first) sb.append(",");
                sb.append(new String(data, StandardCharsets.UTF_8));
                first = false;
            }
            sb.append("]");
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        };

        // Create UnifiedGroupCache
        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "group",
            GroupSettings.defaults(),
            Optional.empty()
        );

        // Create GroupSlice with metadata merging enabled
        final GroupSlice slice = GroupSlice.withMetadataMerging(
            new MapResolver(map),
            "group",
            List.of("local", "proxy"),
            8080,
            cache,
            merger,
            "npm",
            path -> path.endsWith(".json") && !path.contains("/-/")  // NPM metadata pattern
        );

        // Request metadata
        final Response rsp = slice.response(
            new RequestLine("GET", "/test-package.json"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        assertEquals(RsStatus.OK, rsp.status());
        final String body = rsp.body().asString();
        // Should contain merged metadata from both members
        assertTrue(body.contains("test-package"), "Response should contain merged metadata");
    }

    /**
     * Test that non-metadata requests use race strategy even when cache is enabled.
     */
    @Test
    void artifactRequestsUseRaceStrategyWhenCacheEnabled() {
        final Map<String, Slice> map = new HashMap<>();
        map.put("local", new StaticSlice(RsStatus.NOT_FOUND));
        map.put("proxy", (line, headers, body) ->
            CompletableFuture.completedFuture(
                ResponseBuilder.ok().textBody("artifact-data").build()
            )
        );

        // Create UnifiedGroupCache
        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "group",
            GroupSettings.defaults(),
            Optional.empty()
        );

        // Simple merger (won't be used for .tgz)
        final MetadataMerger merger = responses -> new byte[0];

        // Create GroupSlice with metadata merging enabled
        final GroupSlice slice = GroupSlice.withMetadataMerging(
            new MapResolver(map),
            "group",
            List.of("local", "proxy"),
            8080,
            cache,
            merger,
            "npm",
            path -> path.endsWith(".json") && !path.contains("/-/")  // Only .json files
        );

        // Request artifact (not metadata)
        final Response rsp = slice.response(
            new RequestLine("GET", "/package/-/package-1.0.0.tgz"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        assertEquals(RsStatus.OK, rsp.status());
        assertEquals("artifact-data", rsp.body().asString());
    }

    /**
     * Test backward compatibility: GroupSlice without cache works as before.
     */
    @Test
    void backwardCompatibleWithoutCache() {
        final Map<String, Slice> map = new HashMap<>();
        map.put("local", new StaticSlice(RsStatus.NOT_FOUND));
        map.put("proxy", (line, headers, body) ->
            CompletableFuture.completedFuture(
                ResponseBuilder.ok().textBody("found").build()
            )
        );

        // Create GroupSlice without cache (old API)
        final GroupSlice slice = new GroupSlice(
            new MapResolver(map),
            "group",
            List.of("local", "proxy"),
            8080
        );

        // Request should work with race strategy
        final Response rsp = slice.response(
            new RequestLine("GET", "/package.json"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        assertEquals(RsStatus.OK, rsp.status());
        assertEquals("found", rsp.body().asString());
    }

    /**
     * Test that metadata merge returns 404 when no members have the package.
     */
    @Test
    void metadataMergeReturns404WhenNoMembersHavePackage() {
        final Map<String, Slice> map = new HashMap<>();
        map.put("local", new StaticSlice(RsStatus.NOT_FOUND));
        map.put("proxy", new StaticSlice(RsStatus.NOT_FOUND));

        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "group",
            GroupSettings.defaults(),
            Optional.empty()
        );

        final MetadataMerger merger = responses -> new byte[0];

        final GroupSlice slice = GroupSlice.withMetadataMerging(
            new MapResolver(map),
            "group",
            List.of("local", "proxy"),
            8080,
            cache,
            merger,
            "npm",
            path -> path.endsWith(".json")
        );

        final Response rsp = slice.response(
            new RequestLine("GET", "/nonexistent.json"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        assertEquals(RsStatus.NOT_FOUND, rsp.status());
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
