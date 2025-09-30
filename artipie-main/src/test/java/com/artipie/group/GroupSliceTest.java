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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}

