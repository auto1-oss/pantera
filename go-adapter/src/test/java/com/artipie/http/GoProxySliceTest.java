/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http;

import com.artipie.asto.Content;
import com.artipie.asto.cache.Cache;
import com.artipie.http.client.vertx.VertxClientSlices;
import com.artipie.http.hm.RsHasStatus;
import com.artipie.http.hm.SliceHasResponse;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.artipie.http.slice.SliceSimple;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

/**
 * Test for {@link GoProxySlice}.
 *
 * @since 1.0
 */
class GoProxySliceTest {

    private VertxClientSlices client;

    @BeforeEach
    void setUp() {
        this.client = new VertxClientSlices();
        this.client.start();
    }

    @AfterEach
    void tearDown() {
        if (this.client != null) {
            this.client.stop();
        }
    }

    @Test
    void proxiesGetRequestWithMockRemote() {
        final byte[] data = "module example.com/test".getBytes();
        final Slice mockRemote = new SliceSimple(
            ResponseBuilder.ok()
                .header("Content-Type", "text/plain")
                .body(data)
                .build()
        );
        
        final GoProxySlice slice = new GoProxySlice(
            mockRemote,
            Cache.NOP,
            Optional.empty(),
            Optional.empty(),
            "test-repo",
            "go-proxy",
            com.artipie.cooldown.NoopCooldownService.INSTANCE
        );
        
        MatcherAssert.assertThat(
            "Should proxy request successfully",
            slice,
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(RqMethod.GET, "/example.com/test/@v/v1.0.0.mod"),
                Headers.EMPTY,
                Content.EMPTY
            )
        );
    }

    @Test
    void headRequestReturnsHeaders() {
        final Slice mockRemote = new SliceSimple(
            ResponseBuilder.ok()
                .header("Content-Length", "100")
                .build()
        );
        
        final GoProxySlice slice = new GoProxySlice(
            mockRemote,
            Cache.NOP,
            Optional.empty(),
            Optional.empty(),
            "test-repo",
            "go-proxy",
            com.artipie.cooldown.NoopCooldownService.INSTANCE
        );

        MatcherAssert.assertThat(
            slice,
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(RqMethod.HEAD, "/example.com/module/@v/v1.0.0.info"),
                Headers.EMPTY,
                Content.EMPTY
            )
        );
    }

}
