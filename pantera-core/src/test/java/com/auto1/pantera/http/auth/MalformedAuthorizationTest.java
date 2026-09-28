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
package com.auto1.pantera.http.auth;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for B17: an {@code Authorization} header without a
 * scheme (e.g. a raw JWT, the shape {@code mix hex --auth-key} and
 * {@code gem push} send) made {@link Authorization#scheme()} throw an
 * exception carrying the whole credential; the Bearer / Basic / Combined
 * schemes let it escape, so the request answered 500 and the credential was
 * logged at ERROR. An unparseable header must be answered like a missing
 * one (401 + challenge) and the credential must never reach an exception.
 *
 * @since 2.2.9
 */
final class MalformedAuthorizationTest {

    /**
     * A secret-looking header value with no scheme.
     */
    private static final String SECRET = "eyJhbGciOiJSUzI1NiJ9.c2VjcmV0LXBheWxvYWQ.c2lnbmF0dXJl";

    /**
     * Request line (not used by the schemes).
     */
    private static final RequestLine LINE = RequestLine.from("GET /x HTTP/1.1");

    @Test
    void parseFailureDoesNotEchoTheCredential() {
        final IllegalStateException err = Assertions.assertThrows(
            IllegalStateException.class,
            () -> new Authorization(MalformedAuthorizationTest.SECRET).scheme()
        );
        MatcherAssert.assertThat(
            err.getMessage(),
            new IsNot<>(new StringContains(MalformedAuthorizationTest.SECRET))
        );
    }

    @Test
    void bearerSchemeTreatsSchemelessHeaderAsNoCredentials() {
        final AuthScheme.Result result = new BearerAuthScheme(
            tkn -> CompletableFuture.completedFuture(Optional.of(new AuthUser("alice"))),
            "realm=\"pantera\""
        ).authenticate(this.headers(), MalformedAuthorizationTest.LINE)
            .toCompletableFuture().join();
        MatcherAssert.assertThat(
            "a scheme-less header is not a credential",
            result.status(), new IsEqual<>(AuthScheme.AuthStatus.NO_CREDENTIALS)
        );
        MatcherAssert.assertThat(
            "the challenge is still offered",
            result.challenge(), new IsEqual<>("Bearer realm=\"pantera\"")
        );
    }

    @Test
    void basicSchemeTreatsSchemelessHeaderAsNoCredentials() {
        final AuthScheme.Result result = new BasicAuthScheme(
            (name, pass) -> Optional.of(new AuthUser(name))
        ).authenticate(this.headers(), MalformedAuthorizationTest.LINE)
            .toCompletableFuture().join();
        MatcherAssert.assertThat(
            result.status(), new IsEqual<>(AuthScheme.AuthStatus.NO_CREDENTIALS)
        );
    }

    @Test
    void basicSchemeRejectsUndecodableCredentials() {
        final AuthScheme.Result result = new BasicAuthScheme(
            (name, pass) -> Optional.of(new AuthUser(name))
        ).authenticate(
            Headers.from(new Header(Authorization.NAME, "Basic !!not-base64!!")),
            MalformedAuthorizationTest.LINE
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            result.status(), new IsEqual<>(AuthScheme.AuthStatus.FAILED)
        );
    }

    @Test
    void combinedSchemeTreatsSchemelessHeaderAsNoCredentials() {
        final AuthScheme.Result result = new CombinedAuthScheme(
            (name, pass) -> Optional.of(new AuthUser(name)),
            tkn -> CompletableFuture.completedFuture(Optional.of(new AuthUser("alice")))
        ).authenticate(this.headers(), MalformedAuthorizationTest.LINE)
            .toCompletableFuture().join();
        MatcherAssert.assertThat(
            result.status(), new IsEqual<>(AuthScheme.AuthStatus.NO_CREDENTIALS)
        );
    }

    @Test
    void combinedSchemeRejectsUndecodableBasicCredentials() {
        final AuthScheme.Result result = new CombinedAuthScheme(
            (name, pass) -> Optional.of(new AuthUser(name)),
            tkn -> CompletableFuture.completedFuture(Optional.of(new AuthUser("alice")))
        ).authenticate(
            Headers.from(new Header(Authorization.NAME, "Basic !!not-base64!!")),
            MalformedAuthorizationTest.LINE
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            result.status(), new IsEqual<>(AuthScheme.AuthStatus.FAILED)
        );
    }

    @Test
    void authzSliceAnswers401WithChallengeForSchemelessHeader() {
        final RsStatus status = new AuthzSlice(
            (line, headers, body) -> ResponseBuilder.ok().completedFuture(),
            new BearerAuthScheme(
                tkn -> CompletableFuture.completedFuture(Optional.of(new AuthUser("alice"))),
                "realm=\"pantera\""
            ),
            new OperationControl(
                user -> new java.security.Permissions(),
                new com.auto1.pantera.security.perms.AdapterBasicPermission("repo", "read")
            )
        ).response(MalformedAuthorizationTest.LINE, this.headers(), Content.EMPTY)
            .join().status();
        MatcherAssert.assertThat(status, new IsEqual<>(RsStatus.UNAUTHORIZED));
    }

    private Headers headers() {
        return Headers.from(new Header(Authorization.NAME, MalformedAuthorizationTest.SECRET));
    }
}
