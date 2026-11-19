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
package com.auto1.pantera.http.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The standard cache-miss flow of {@link BaseCachedProxySlice} on a real
 * {@link FileStorage}: an artifact of any size is fetched, stored and served.
 *
 * <p>The flow spools the upstream body to a temp file and saves that file to
 * storage. The file writer asks its source for more chunks while it still has
 * writes queued, so the source is asked again after it has signalled
 * completion; an in-memory storage never does that, which is why the other
 * suites of this class do not cover it. A source that answered the late
 * request by reading its closed file failed the save, and the client got a
 * 5xx for an artifact the upstream had just served.</p>
 *
 * @since 2.2.12
 */
final class BaseCachedProxySliceFileStorageTest {

    /**
     * Read chunk of the spooled temp file.
     */
    private static final int CHUNK = 65_536;

    /**
     * An archive that is not one of the Maven primary extensions, so it takes
     * the standard flow.
     */
    private static final String PATH = "/org/example/dist/1.0/dist-1.0-bin.tar.gz";

    @ParameterizedTest(name = "{0} bytes")
    @ValueSource(ints = {
        0, 1, CHUNK - 1, CHUNK, CHUNK + 1,
        3 * CHUNK, 4 * CHUNK + 1, 16 * CHUNK, 1_000_000, 8_842_660
    })
    // The bound only turns a hung save into a failure; it is not a latency claim.
    @Timeout(120)
    void fetchesStoresAndServesArtifact(final int size, @TempDir final Path dir)
        throws Exception {
        final byte[] payload = new byte[size];
        new Random(size).nextBytes(payload);
        final Storage storage = new FileStorage(dir);
        final Slice upstream = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok().body(payload).build()
        );
        final Response response = new TestSlice(upstream, storage).response(
            new RequestLine(RqMethod.GET, BaseCachedProxySliceFileStorageTest.PATH),
            Headers.EMPTY, Content.EMPTY
        ).get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "an artifact the upstream served is answered 200",
            response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the client receives the upstream bytes",
            response.body().asBytesFuture().get(60, TimeUnit.SECONDS),
            new IsEqual<>(payload)
        );
        MatcherAssert.assertThat(
            "the artifact is stored byte for byte",
            storage.value(
                new Key.From(BaseCachedProxySliceFileStorageTest.PATH.substring(1))
            ).get(60, TimeUnit.SECONDS).asBytesFuture().get(60, TimeUnit.SECONDS),
            new IsEqual<>(payload)
        );
    }

    /**
     * Minimal subclass: every path is cacheable and storage-backed.
     */
    private static final class TestSlice extends BaseCachedProxySlice {

        TestSlice(final Slice upstream, final Storage storage) {
            super(
                upstream,
                new FromStorageCache(storage),
                "test-repo",
                "test",
                "http://upstream",
                Optional.of(storage),
                Optional.empty(),
                ProxyCacheConfig.defaults()
            );
        }

        @Override
        protected boolean isCacheable(final String path) {
            return true;
        }
    }
}
