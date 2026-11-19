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
package com.auto1.pantera.hex.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.security.policy.Policy;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * A Hex repository answers {@code HEAD} for a tarball it serves on
 * {@code GET}: {@code curl -I} and tools that probe a download before
 * fetching it got 404 for a release that exists.
 *
 * @since 2.2.12
 */
final class HexSliceHeadTest {

    /**
     * Tarball path.
     */
    private static final String PATH = "/tarballs/decimal-2.0.0.tar";

    @Test
    void answersHeadOfTarballLikeGetWithoutBody() throws Exception {
        final Storage storage = new InMemoryStorage();
        storage.save(
            new Key.From(HexSliceHeadTest.PATH.substring(1)),
            new Content.From("hex tarball".getBytes(StandardCharsets.UTF_8))
        ).get(30, TimeUnit.SECONDS);
        final HexSlice slice = new HexSlice(
            storage, Policy.FREE,
            (name, pass) -> Optional.of(new AuthUser(name, "test")),
            Optional.empty(), "my-hex"
        );
        final Headers auth = Headers.from(new Authorization.Basic("alice", "secret"));
        final Response get = slice.response(
            new RequestLine(RqMethod.GET, HexSliceHeadTest.PATH), auth, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        final Response head = slice.response(
            new RequestLine(RqMethod.HEAD, HexSliceHeadTest.PATH), auth, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "GET serves the tarball", get.status(), new IsEqual<>(RsStatus.OK)
        );
        get.body().asBytesFuture().get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "HEAD answers like GET", head.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "HEAD has no body",
            head.body().asBytesFuture().get(30, TimeUnit.SECONDS).length, new IsEqual<>(0)
        );
    }
}
