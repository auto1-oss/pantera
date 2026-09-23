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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link CondaUrlTokenSlice}.
 *
 * @since 2.2.9
 */
final class CondaUrlTokenSliceTest {

    @Test
    void liftsTokenAfterRepositoryName() {
        MatcherAssert.assertThat(
            CondaUrlTokenSliceTest.seen(true, "/my-conda/t/abc/noarch/repodata.json", Headers.EMPTY),
            new IsEqual<>(List.of("token abc"))
        );
    }

    @Test
    void liftsTokenAtRootOfDedicatedPort() {
        MatcherAssert.assertThat(
            CondaUrlTokenSliceTest.seen(false, "/t/abc/noarch/repodata.json", Headers.EMPTY),
            new IsEqual<>(List.of("token abc"))
        );
    }

    @Test
    void leavesPathsWithoutTokenAlone() {
        MatcherAssert.assertThat(
            CondaUrlTokenSliceTest.seen(true, "/my-conda/noarch/repodata.json", Headers.EMPTY),
            new IsEqual<>(List.of())
        );
    }

    @Test
    void keepsExplicitAuthorization() {
        MatcherAssert.assertThat(
            CondaUrlTokenSliceTest.seen(
                true, "/my-conda/t/abc/noarch/repodata.json",
                Headers.from(new Authorization.Basic("alice", "pw"))
            ),
            new IsEqual<>(List.of(new Authorization.Basic("alice", "pw").getValue()))
        );
    }

    /**
     * Authorization values the origin sees.
     * @param prefixed Main-port paths
     * @param path Request path
     * @param headers Request headers
     * @return Authorization values
     */
    private static List<String> seen(final boolean prefixed, final String path,
        final Headers headers) {
        final AtomicReference<Headers> seen = new AtomicReference<>();
        new CondaUrlTokenSlice(
            (line, hdrs, body) -> {
                seen.set(hdrs);
                return ResponseBuilder.ok().completedFuture();
            },
            prefixed
        ).response(new RequestLine(RqMethod.GET, path), headers, Content.EMPTY).join();
        return seen.get().values(Authorization.NAME);
    }
}
