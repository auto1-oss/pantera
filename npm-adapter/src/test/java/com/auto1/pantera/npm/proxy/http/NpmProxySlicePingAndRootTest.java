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
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.cooldown.metadata.NoopCooldownMetadataService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.npm.proxy.NpmProxy;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonObject;

/**
 * <p><b>WS8 Bug B3</b> (MEDIUM): {@code npm ping} was not wired for
 * {@code NpmProxySlice} at all -- {@code GET /-/ping} fell through every
 * route to the generic 404 stub. A proxy ping must answer from Pantera
 * itself, never requiring an upstream round-trip.</p>
 *
 * <p><b>WS8 Bug B4</b> (MEDIUM): the registry-root route
 * ({@code RegistryInfoSlice}) only existed for local repositories --
 * {@code GET <repoBase>} 404'd on a proxy repository in every case.</p>
 *
 * <p>Both assertions prove the response came from Pantera directly (never
 * touching {@code remote}), the same discriminator {@code
 * NpmProxySliceDistTagsHeadTest}/{@code NpmProxySliceHeadTarballTest} use.</p>
 */
final class NpmProxySlicePingAndRootTest {

    @Test
    void pingIsAnsweredWithoutTouchingUpstream() throws Exception {
        final RecordingSlice remote = new RecordingSlice();
        final NpmProxySlice slice = this.slice(remote);
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/-/ping"), Headers.EMPTY, Content.EMPTY
        ).get();
        MatcherAssert.assertThat(response.status(), new IsEqual<>(RsStatus.OK));
        MatcherAssert.assertThat(
            "a proxy ping must be answered by Pantera directly, never by an "
                + "upstream round-trip",
            remote.invoked(),
            new IsEqual<>(false)
        );
    }

    @Test
    void registryRootIsReachableWithoutTouchingUpstream() throws Exception {
        final RecordingSlice remote = new RecordingSlice();
        final NpmProxySlice slice = this.slice(remote);
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/"), Headers.EMPTY, Content.EMPTY
        ).get();
        MatcherAssert.assertThat(response.status(), new IsEqual<>(RsStatus.OK));
        final JsonObject body = Json.createReader(
            new StringReader(response.body().asString())
        ).readObject();
        MatcherAssert.assertThat(
            "the repository root must answer with Pantera's own registry info",
            body.getBoolean("pantera"),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the registry root must be answered by Pantera directly, never by "
                + "an upstream round-trip",
            remote.invoked(),
            new IsEqual<>(false)
        );
    }

    private NpmProxySlice slice(final Slice remote) {
        return new NpmProxySlice(
            "",
            new NpmProxy(new InMemoryStorage(), remote),
            Optional.empty(),
            "npm-proxy",
            "npm",
            NoopCooldownService.INSTANCE,
            NoopCooldownMetadataService.INSTANCE,
            remote
        );
    }

    /**
     * Records whether it was ever invoked, so a test can assert the
     * request never left Pantera to reach the (simulated) upstream.
     */
    private static final class RecordingSlice implements Slice {

        /**
         * Whether {@link #response} was called.
         */
        private volatile boolean invoked;

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            this.invoked = true;
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        }

        boolean invoked() {
            return this.invoked;
        }
    }
}
