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
package com.auto1.pantera.conda.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link CondaRootTokenSlice}: the conda CLI moves a channel's
 * {@code /t/<token>} right after the host, in front of the whole path.
 *
 * @since 2.2.9
 */
final class CondaRootTokenSliceTest {

    /**
     * JWT-shaped token.
     */
    private static final String JWT = "eyJh.eyJz.c2ln";

    @Test
    void liftsHexEncodedJwtInFrontOfThePath() {
        final Seen seen = CondaRootTokenSliceTest.seen(
            String.format(
                "/t/%s/test_prefix/api/my-conda/noarch/repodata.json?x=1",
                CondaRootTokenSliceTest.hex(CondaRootTokenSliceTest.JWT)
            ),
            Headers.EMPTY
        );
        MatcherAssert.assertThat(
            "token segment removed from the path",
            seen.line.get().uri().toString(),
            new IsEqual<>("/test_prefix/api/my-conda/noarch/repodata.json?x=1")
        );
        MatcherAssert.assertThat(
            "token presented as the conda token header",
            seen.headers.get().values(Authorization.NAME),
            new IsEqual<>(List.of(String.format("token %s", CondaRootTokenSliceTest.JWT)))
        );
    }

    @Test
    void liftsRawJwtInFrontOfThePath() {
        final Seen seen = CondaRootTokenSliceTest.seen(
            String.format(
                "/t/%s/my-conda/linux-64/pkg-1.0-0.tar.bz2", CondaRootTokenSliceTest.JWT
            ),
            Headers.EMPTY
        );
        MatcherAssert.assertThat(
            seen.line.get().uri().getPath(),
            new IsEqual<>("/my-conda/linux-64/pkg-1.0-0.tar.bz2")
        );
    }

    @Test
    void leavesSegmentThatIsNotATokenAlone() {
        // A repository named "t" keeps its paths.
        final Seen seen = CondaRootTokenSliceTest.seen("/t/docs/readme.txt", Headers.EMPTY);
        MatcherAssert.assertThat(
            "path unchanged",
            seen.line.get().uri().getPath(), new IsEqual<>("/t/docs/readme.txt")
        );
        MatcherAssert.assertThat(
            "no credentials added",
            seen.headers.get().values(Authorization.NAME), new IsEqual<>(List.of())
        );
    }

    @Test
    void keepsExplicitAuthorization() {
        final Seen seen = CondaRootTokenSliceTest.seen(
            String.format("/t/%s/my-conda/noarch/repodata.json", CondaRootTokenSliceTest.JWT),
            Headers.from(new Authorization.Basic("alice", "pw"))
        );
        MatcherAssert.assertThat(
            seen.headers.get().values(Authorization.NAME),
            new IsEqual<>(List.of(new Authorization.Basic("alice", "pw").getValue()))
        );
    }

    /**
     * Lower-case hex of an ASCII string.
     * @param value Value
     * @return Hex
     */
    private static String hex(final String value) {
        final StringBuilder res = new StringBuilder();
        for (final byte chr : value.getBytes(StandardCharsets.US_ASCII)) {
            res.append(String.format("%02x", chr));
        }
        return res.toString();
    }

    /**
     * What the origin sees.
     * @param path Request path
     * @param headers Request headers
     * @return Seen request
     */
    private static Seen seen(final String path, final Headers headers) {
        final Seen seen = new Seen();
        new CondaRootTokenSlice(
            (line, hdrs, body) -> {
                seen.line.set(line);
                seen.headers.set(hdrs);
                return ResponseBuilder.ok().completedFuture();
            }
        ).response(new RequestLine(RqMethod.GET, path), headers, Content.EMPTY).join();
        return seen;
    }

    /**
     * Request the origin saw.
     * @since 2.2.9
     */
    private static final class Seen {

        /**
         * Request line.
         */
        private final AtomicReference<RequestLine> line = new AtomicReference<>();

        /**
         * Headers.
         */
        private final AtomicReference<Headers> headers = new AtomicReference<>();
    }
}
