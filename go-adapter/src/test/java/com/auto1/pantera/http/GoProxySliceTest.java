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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.http.client.jetty.JettyClientSlices;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.SliceSimple;
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

    private JettyClientSlices client;

    @BeforeEach
    void setUp() {
        this.client = new JettyClientSlices();
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
            com.auto1.pantera.cooldown.impl.NoopCooldownService.INSTANCE
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
            com.auto1.pantera.cooldown.impl.NoopCooldownService.INSTANCE
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
