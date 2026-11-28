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
package com.auto1.pantera.npm.http.auth;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthRefusal;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.LoginSource;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.json.Json;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * {@code npm login} tells the client why its login is refused when the
 * sign-in chain knows: the account is locked, or logins are delayed.
 */
final class OAuthLoginRefusalTest {

    /**
     * Login path of the npm client.
     */
    private static final String PATH = "/-/user/org.couchdb.user:alice";

    @Test
    void lockedAccountIsAnswered403() {
        final Response response = OAuthLoginRefusalTest.login(
            new AuthRefusal("account_locked", RsStatus.FORBIDDEN, 0L, "Account locked"), null
        );
        MatcherAssert.assertThat(
            "status", response.status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "the npm client prints this error",
            Json.createReader(new StringReader(response.body().asString()))
                .readObject().getString("error"),
            new IsEqual<>("Account locked")
        );
    }

    @Test
    void delayedLoginIsAnswered403WithTheWait() {
        final Response response = OAuthLoginRefusalTest.login(
            new AuthRefusal(
                "login_delayed", RsStatus.FORBIDDEN, 9L,
                "Too many failed logins. Try again in 9 seconds."
            ),
            null
        );
        MatcherAssert.assertThat(
            "status", response.status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "the wait", response.headers().values("Retry-After"), new IsEqual<>(List.of("9"))
        );
    }

    @Test
    void refusalWithoutTextKeepsTheGenericError() {
        final Response response =
            OAuthLoginRefusalTest.login(new AuthRefusal("mfa_required"), null);
        MatcherAssert.assertThat(
            "status", response.status(), new IsEqual<>(RsStatus.UNAUTHORIZED)
        );
        MatcherAssert.assertThat(
            "error",
            Json.createReader(new StringReader(response.body().asString()))
                .readObject().getString("error"),
            new IsEqual<>("invalid credentials")
        );
    }

    @Test
    void chainIsToldWhereTheLoginComesFrom() {
        final AtomicReference<LoginSource> seen = new AtomicReference<>();
        OAuthLoginRefusalTest.login(null, seen);
        MatcherAssert.assertThat(
            seen.get(),
            new IsEqual<>(new LoginSource("192.0.2.7", "npm/10.8.2 node/v22", "repository"))
        );
    }

    /**
     * Send one {@code npm login} for a chain that refuses it.
     * @param refusal What the chain notes, or null
     * @param seen Receives the login source the chain saw, or null
     * @return Response
     */
    private static Response login(
        final AuthRefusal refusal, final AtomicReference<LoginSource> seen
    ) {
        final Authentication auth = (name, pass) -> {
            if (seen != null) {
                seen.set(LoginSource.current());
            }
            if (refusal != null) {
                refusal.note();
            }
            return Optional.empty();
        };
        final Tokens tokens = new Tokens() {
            @Override
            public TokenAuthentication auth() {
                return token -> CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public String generate(final AuthUser user) {
                return "access:" + user.name();
            }
        };
        return new OAuthLoginSlice(auth, tokens).response(
            new RequestLine(RqMethod.PUT, OAuthLoginRefusalTest.PATH),
            Headers.from(new Header("User-Agent", "npm/10.8.2 node/v22"))
                .add(new Header(EcsLoggingSlice.CTX_CLIENT_IP_HEADER, "192.0.2.7")),
            new Content.From(
                "{\"name\":\"alice\",\"password\":\"secret\"}".getBytes(StandardCharsets.UTF_8)
            )
        ).join();
    }
}
