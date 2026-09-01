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
package com.auto1.pantera.gem;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.gem.http.GemSlice;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.security.policy.Policy;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test for the {@code Authorization}-header-presence
 * bypass on the gem disclosure routes.
 *
 * <p>Before 2.2.9 {@code GET /api/v1/dependencies} ({@code DepsGemSlice})
 * and {@code GET /api/v1/gems/<name>.json} ({@code ApiGetSlice}) were
 * routed with NO auth wrapper in {@link GemSlice}, while the upload and the
 * fallback GET used {@code createAuthSlice}. Behind the presence-only
 * {@code AnonymousAccessSlice} gate, a request carrying any bogus
 * {@code Authorization} header therefore disclosed private-repository gem
 * metadata and dependency data.</p>
 *
 * @since 2.2.9
 */
final class GemDisclosureAuthBypassTest {

    @Test
    void bogusBearerCannotReadDependencies() {
        MatcherAssert.assertThat(
            "a bogus bearer must be rejected (401) on /api/v1/dependencies "
                + "before the dependency resolver runs",
            status("/api/v1/dependencies?gems=rails"),
            new IsEqual<>(401)
        );
    }

    @Test
    void bogusBearerCannotReadGemInfo() {
        MatcherAssert.assertThat(
            "a bogus bearer must be rejected (401) on /api/v1/gems/<name>.json "
                + "before the gem lookup runs",
            status("/api/v1/gems/rails.json"),
            new IsEqual<>(401)
        );
    }

    /**
     * Issue a GET with a bogus bearer against a gem slice whose Basic
     * authentication rejects everyone and whose token authentication knows
     * no tokens. A request that reaches the disclosure sink either answers
     * non-401 or fails looking up the (absent) gem — both mean the credential
     * was never checked; that outcome is reported as {@code -1}.
     */
    private static int status(final String path) {
        try {
            final Response response = new GemSlice(
                new InMemoryStorage(),
                Policy.FREE,
                (username, password) -> Optional.empty(),
                token -> CompletableFuture.completedFuture(Optional.<AuthUser>empty()),
                "gems-private",
                Optional.empty()
            ).response(
                new RequestLine(RqMethod.GET, path),
                Headers.from(new Authorization.Bearer("garbage")),
                Content.EMPTY
            ).toCompletableFuture().join();
            return response.status().code();
        } catch (final RuntimeException sinkFailure) {
            return -1;
        }
    }
}
