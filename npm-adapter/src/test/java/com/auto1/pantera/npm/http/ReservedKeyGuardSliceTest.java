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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.util.regex.Pattern;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Security regression test for {@link ReservedKeyGuardSlice} (guards against
 * the C1 finding: {@code GET /<repo>/.registry-keys.json} exfiltrating the
 * registry's ECDSA private signing key, and {@code _users/}/{@code _tokens/}
 * records, via the raw {@code .*\.json$} content route).
 */
final class ReservedKeyGuardSliceTest {

    @Test
    void guardAlwaysAnswersNotFound() {
        MatcherAssert.assertThat(
            "a request routed to the reserved-key guard must 404, never serve the file",
            new ReservedKeyGuardSlice().response(
                new RequestLine(RqMethod.GET, "/.registry-keys.json"),
                Headers.EMPTY, Content.EMPTY
            ).join().status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    @Test
    void reservedPatternMatchesTheSigningKeyAndAuthRecords() {
        for (final String path : new String[]{
            "/.registry-keys.json",
            "/_users",
            "/_users/alice.json",
            "/_tokens",
            "/_tokens/deadbeef.json",
        }) {
            MatcherAssert.assertThat(
                "reserved internal key must be caught by the guard route: " + path,
                Pattern.matches(ReservedKeyGuardSlice.RESERVED_PATH, path),
                new IsEqual<>(true)
            );
        }
    }

    @Test
    void reservedPatternDoesNotShadowLegitimateNpmRoutes() {
        for (final String path : new String[]{
            "/simple-npm-project",
            "/@scope/pkg",
            "/simple-npm-project/1.0.0",
            "/simple-npm-project/latest",
            "/simple-npm-project/-/simple-npm-project-1.0.0.tgz",
            "/simple-npm-project/meta.json",
            "/-/v1/search",
            "/-/npm/v1/keys",
            "/_usersfoo.json",
        }) {
            MatcherAssert.assertThat(
                "legitimate npm route must NOT be shadowed by the reserved-key guard: " + path,
                Pattern.matches(ReservedKeyGuardSlice.RESERVED_PATH, path),
                new IsEqual<>(false)
            );
        }
    }
}
