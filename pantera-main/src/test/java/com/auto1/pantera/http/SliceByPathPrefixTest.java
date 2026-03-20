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

    /**
     * Subclass of RepositorySlices that records which Key was passed to slice().
     */
    private static final class RecordingSlices extends RepositorySlices {
        /**
         * Keys that were requested.
         */
        private final List<String> keys;

        RecordingSlices() {
            super(new TestSettings(), null, null);
            this.keys = Collections.synchronizedList(new ArrayList<>(4));
        }

        @Override
        public Slice slice(final Key name, final int port) {
            this.keys.add(name.string());
            return (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().build()
            );
        }

        String lastKey() {
            return this.keys.get(this.keys.size() - 1);
        }
    }
}
