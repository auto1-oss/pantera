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

import com.auto1.pantera.RepositorySlices;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.settings.PrefixesConfig;
import com.auto1.pantera.test.TestSettings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link SliceByPath} with prefix support.
 */
class SliceByPathPrefixTest {

    @Test
    void routesUnprefixedPath() {
        final PrefixesConfig prefixes = new PrefixesConfig(Arrays.asList("p1", "p2"));
        final RecordingSlices slices = new RecordingSlices();
        new SliceByPath(slices, prefixes).response(
            new RequestLine(RqMethod.GET, "/test/artifact.jar"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals("test", slices.lastKey());
    }

    @Test
    void stripsPrefixFromPath() {
        final PrefixesConfig prefixes = new PrefixesConfig(Arrays.asList("p1", "p2"));
        final RecordingSlices slices = new RecordingSlices();
        new SliceByPath(slices, prefixes).response(
            new RequestLine(RqMethod.GET, "/p1/test/artifact.jar"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals("test", slices.lastKey());
    }

    @Test
    void stripsMultiplePrefixes() {
        final PrefixesConfig prefixes = new PrefixesConfig(
            Arrays.asList("p1", "p2", "migration")
        );
        final RecordingSlices s1 = new RecordingSlices();
        new SliceByPath(s1, prefixes).response(
            new RequestLine(RqMethod.GET, "/p1/maven/artifact.jar"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals("maven", s1.lastKey());
        final RecordingSlices s2 = new RecordingSlices();
        new SliceByPath(s2, prefixes).response(
            new RequestLine(RqMethod.GET, "/p2/npm/package.tgz"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals("npm", s2.lastKey());
        final RecordingSlices s3 = new RecordingSlices();
        new SliceByPath(s3, prefixes).response(
            new RequestLine(RqMethod.GET, "/migration/docker/image"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals("docker", s3.lastKey());
    }

    @Test
    void doesNotStripUnknownPrefix() {
        final PrefixesConfig prefixes = new PrefixesConfig(Arrays.asList("p1", "p2"));
        final RecordingSlices slices = new RecordingSlices();
        new SliceByPath(slices, prefixes).response(
            new RequestLine(RqMethod.GET, "/unknown/test/artifact.jar"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals("unknown", slices.lastKey());
    }

    @Test
    void handlesEmptyPrefixList() {
        final PrefixesConfig prefixes = new PrefixesConfig();
        final RecordingSlices slices = new RecordingSlices();
        new SliceByPath(slices, prefixes).response(
            new RequestLine(RqMethod.GET, "/test/artifact.jar"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals("test", slices.lastKey());
    }

    @Test
    void supportsAllHttpMethods() {
        final PrefixesConfig prefixes = new PrefixesConfig(Arrays.asList("p1"));
        for (final RqMethod method : Arrays.asList(
            RqMethod.GET, RqMethod.HEAD, RqMethod.PUT, RqMethod.POST, RqMethod.DELETE
        )) {
            final RecordingSlices slices = new RecordingSlices();
            new SliceByPath(slices, prefixes).response(
                new RequestLine(method, "/p1/test/artifact.jar"),
                Headers.EMPTY,
                Content.EMPTY
            ).join();
            assertEquals("test", slices.lastKey());
        }
    }

    @Test
    void handlesRootPath() {
        final PrefixesConfig prefixes = new PrefixesConfig(Arrays.asList("p1"));
        final RecordingSlices slices = new RecordingSlices();
        final Response response = new SliceByPath(slices, prefixes).response(
            new RequestLine(RqMethod.GET, "/"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals(404, response.status().code());
    }

    @Test
    void handlesPrefixOnlyPath() {
        final PrefixesConfig prefixes = new PrefixesConfig(Arrays.asList("p1"));
        final RecordingSlices slices = new RecordingSlices();
        final Response response = new SliceByPath(slices, prefixes).response(
            new RequestLine(RqMethod.GET, "/p1"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals(404, response.status().code());
    }

    @ParameterizedTest
    @CsvSource({
        "/p1/files/col/report%231.txt,/files/col/report%231.txt,/files/col/report#1.txt",
        "/p1/files/col/q%3F.txt,/files/col/q%3F.txt,/files/col/q?.txt",
        "/p1/files/col/a%20b.txt,/files/col/a%20b.txt,/files/col/a b.txt",
        "/p1/files/col/q%2525A.txt,/files/col/q%2525A.txt,/files/col/q%25A.txt",
        "/p1/files/col/%252e%252e/x,/files/col/%252e%252e/x,/files/col/%2e%2e/x"
    })
    void keepsPercentEncodingWhenStrippingPrefix(final String sent,
        final String raw, final String decoded) {
        final RecordingSlices slices = new RecordingSlices();
        final Response rsp = new SliceByPath(
            slices, new PrefixesConfig(Arrays.asList("p1"))
        ).response(
            new RequestLine(RqMethod.GET, sent),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals(200, rsp.status().code(), "request is routed");
        assertEquals("files", slices.lastKey(), "repository key");
        assertEquals(raw, slices.lastLine().uri().getRawPath(), "raw path keeps encoding");
        assertEquals(decoded, slices.lastLine().uri().getPath(), "decoded exactly once");
    }

    @Test
    void keepsRawQueryWhenStrippingPrefix() {
        final RecordingSlices slices = new RecordingSlices();
        new SliceByPath(slices, new PrefixesConfig(Arrays.asList("p1"))).response(
            new RequestLine(RqMethod.GET, "/p1/files/list?q=a%26b%3Dc&x=%23"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals("q=a%26b%3Dc&x=%23", slices.lastLine().uri().getRawQuery());
    }

    /**
     * Subclass of RepositorySlices that records which Key was passed to slice().
     */
    private static final class RecordingSlices extends RepositorySlices {
        /**
         * Keys that were requested.
         */
        private final List<String> keys;

        /**
         * Request lines the returned slices received.
         */
        private final List<RequestLine> lines;

        RecordingSlices() {
            super(new TestSettings(), null, null);
            this.keys = Collections.synchronizedList(new ArrayList<>(4));
            this.lines = Collections.synchronizedList(new ArrayList<>(4));
        }

        @Override
        public Slice slice(final Key name, final int port) {
            this.keys.add(name.string());
            return (line, headers, body) -> {
                this.lines.add(line);
                return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
            };
        }

        RequestLine lastLine() {
            return this.lines.get(this.lines.size() - 1);
        }

        String lastKey() {
            return this.keys.get(this.keys.size() - 1);
        }
    }
}
