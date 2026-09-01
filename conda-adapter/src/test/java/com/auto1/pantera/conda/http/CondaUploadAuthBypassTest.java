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
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.security.policy.Policy;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test for the {@code Authorization}-header-presence
 * bypass on the conda package upload route.
 *
 * <p>Before 2.2.9 the {@code POST /<pkg>/<arch>/<file>.tar.bz2} route in
 * {@link CondaSlice} invoked {@code UpdateSlice} with NO auth wrapper,
 * while every sibling route wrapped {@code TokenAuthSlice} /
 * {@code BasicAuthzSlice} + {@code OperationControl}. The outer
 * per-repository {@code AnonymousAccessSlice} gate only checks that SOME
 * {@code Authorization} header is present, so a request carrying
 * {@code Authorization: token bogus} uploaded an attacker-crafted package
 * into a deny-by-default private channel (repodata merge + index event —
 * supply-chain poisoning).</p>
 *
 * @since 2.2.9
 */
final class CondaUploadAuthBypassTest {

    @Test
    void bogusTokenCannotUploadAPackage() throws Exception {
        final Storage storage = new InMemoryStorage();
        final CondaSlice slice = new CondaSlice(
            storage,
            Policy.FREE,
            (username, password) -> Optional.empty(),
            new RejectingTokens(),
            "http://localhost",
            "conda-private",
            Optional.empty()
        );
        // An upload that reaches the sink either writes and then fails to
        // parse the bogus archive (exception) or answers non-401; only a
        // credential check in front of the sink yields a clean 401.
        int status;
        try {
            final Response response = slice.response(
                new RequestLine(RqMethod.POST, "/evil/noarch/evil-1.0-0.tar.bz2"),
                Headers.from(new Header("Authorization", "token bogus")),
                new Content.From("not really a conda package".getBytes(StandardCharsets.UTF_8))
            ).toCompletableFuture().join();
            status = response.status().code();
        } catch (final RuntimeException sinkFailure) {
            status = -1;
        }
        MatcherAssert.assertThat(
            "a bogus conda token must be rejected with 401 before the upload sink runs",
            status, new IsEqual<>(401)
        );
        MatcherAssert.assertThat(
            "nothing may be written to the repository by an unauthenticated upload",
            storage.list(Key.ROOT).join().isEmpty(), new IsEqual<>(true)
        );
    }

    /**
     * Token authentication that recognises NO token.
     */
    private static final class RejectingTokens implements Tokens {

        @Override
        public TokenAuthentication auth() {
            return token -> CompletableFuture.completedFuture(Optional.<AuthUser>empty());
        }

        @Override
        public String generate(final AuthUser user) {
            throw new UnsupportedOperationException("not used");
        }
    }
}
