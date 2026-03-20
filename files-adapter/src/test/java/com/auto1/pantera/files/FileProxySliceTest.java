/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.files;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.cache.StreamThroughCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.RsHasBody;
import com.auto1.pantera.http.hm.RsHasHeaders;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.slice.SliceSimple;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.collection.IsEmptyIterable;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests for {@link FileProxySlice}.
 */
final class FileProxySliceTest {

    private Storage storage;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
    }

    @Test
    void sendEmptyHeadersAndContent() throws Exception {
        final AtomicReference<Iterable<Header>> headers;
        headers = new AtomicReference<>();
        final AtomicReference<byte[]> body = new AtomicReference<>();
        new FileProxySlice(
            new FakeClientSlices(
                (rqline, rqheaders, rqbody) -> {
                    headers.set(rqheaders);
                    return new Content.From(rqbody).asBytesFuture().thenApply(
                        bytes -> {
                            body.set(bytes);
                            return ResponseBuilder.ok().build();
                        }
                    );
                }
            ),
            new URI("http://host/path")
        ).response(
            new RequestLine(RqMethod.GET, "/"),
            Headers.from("X-Name", "Value"),
            new Content.From("data".getBytes())
        ).join();
        MatcherAssert.assertThat(
            "Headers are empty",
            headers.get(),
            new IsEmptyIterable<>()
        );
        MatcherAssert.assertThat(
            "Body is empty",
            body.get(),
            new IsEqual<>(new byte[0])
        );
    }

    @Test
    void getsContentFromRemoteAndAdsItToCache() {
        final byte[] body = "some".getBytes();
        final String key = "any";
        MatcherAssert.assertThat(
            "Should returns body from remote",
            new FileProxySlice(
                new SliceSimple(
                    ResponseBuilder.ok().header("header", "value")
                        .body(body)
                        .build()
                ),
                new StreamThroughCache(this.storage)
            ),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasBody(body),
                    new RsHasHeaders(
                        new Header("header", "value"),
                        new Header("Content-Length", "4"),
                        new Header("Content-Length", "4")
                    )
                ),
                new RequestLine(RqMethod.GET, String.format("/%s", key))
            )
        );
        MatcherAssert.assertThat(
            "Does not store data in cache",
            new BlockingStorage(this.storage).value(new Key.From(key)),
            new IsEqual<>(body)
        );
    }

    @Test
    void getsFromCacheOnError() {
        final byte[] body = "abc123".getBytes();
        final String key = "any";
        this.storage.save(new Key.From(key), new Content.From(body)).join();
        MatcherAssert.assertThat(
            "Does not return body from cache",
            new FileProxySlice(
                new SliceSimple(ResponseBuilder.internalError().build()),
                new StreamThroughCache(this.storage)
            ),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasStatus(RsStatus.OK), new RsHasBody(body),
                    new RsHasHeaders(
                        new Header("Content-Length", String.valueOf(body.length))
                    )
                ),
                new RequestLine(RqMethod.GET, String.format("/%s", key))
            )
        );
        MatcherAssert.assertThat(
            "Data should stays intact in cache",
            new BlockingStorage(this.storage).value(new Key.From(key)),
            new IsEqual<>(body)
        );
    }

    @Test
    void returnsNotFoundWhenRemoteReturnedBadRequest() {
        MatcherAssert.assertThat(
            "Incorrect status, 404 is expected",
            new FileProxySlice(
                new SliceSimple(ResponseBuilder.badRequest().build()),
                new StreamThroughCache(this.storage)
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.NOT_FOUND),
                new RequestLine(RqMethod.GET, "/any")
            )
        );
        MatcherAssert.assertThat(
            "Cache storage is not empty",
            this.storage.list(Key.ROOT).join().isEmpty(),
            new IsEqual<>(true)
        );
    }

    /**
     * Fake {@link ClientSlices} implementation that returns specified result.
     *
     * @since 0.7
     */
    private static final class FakeClientSlices implements ClientSlices {

        /**
         * Slice returned by requests.
         */
        private final Slice result;

        /**
         * Ctor.
         *
         * @param result Slice returned by requests.
         */
        FakeClientSlices(final Slice result) {
            this.result = result;
        }

        @Override
        public Slice http(final String host) {
            return this.result;
        }

        @Override
        public Slice http(final String host, final int port) {
            return this.result;
        }

        @Override
        public Slice https(final String host) {
            return this.result;
        }

        @Override
        public Slice https(final String host, final int port) {
            return this.result;
        }
    }
}
