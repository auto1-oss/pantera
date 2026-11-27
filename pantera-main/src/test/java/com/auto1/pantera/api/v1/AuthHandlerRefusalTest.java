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
package com.auto1.pantera.api.v1;

import com.auto1.pantera.auth.JwtTokens;
import com.auto1.pantera.auth.failedlogins.FailedLogins;
import com.auto1.pantera.auth.failedlogins.GuardedPasswords;
import com.auto1.pantera.auth.failedlogins.InMemoryFailedLoginStore;
import com.auto1.pantera.auth.failedlogins.LockedOut;
import com.auto1.pantera.auth.failedlogins.LoginOrigin;
import com.auto1.pantera.auth.signin.InMemorySignInFailureStore;
import com.auto1.pantera.auth.signin.SignInFailure;
import com.auto1.pantera.auth.signin.SignInFailureStore;
import com.auto1.pantera.auth.signin.SignInFailures;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthRefusal;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.LoginSource;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.policy.FailedLoginsConfig;
import com.auto1.pantera.settings.policy.SignInReportConfig;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.junit5.VertxExtension;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The sign-in endpoint tells a person why a sign-in is refused when the
 * sign-in chain knows: the account is locked, logins are delayed or
 * paused, the identity provider cannot be reached. Everything else stays
 * the one generic message, an unconfirmed second factor included.
 *
 * @since 2.2.15
 */
@ExtendWith(VertxExtension.class)
final class AuthHandlerRefusalTest {

    /**
     * What the chain notes before it refuses the next login, if anything.
     */
    private final AtomicReference<AuthRefusal> refusal = new AtomicReference<>();

    /**
     * Where the chain saw the last login come from.
     */
    private final AtomicReference<LoginSource> source = new AtomicReference<>();

    /**
     * Failed logins of the installation.
     */
    private FailedLogins logins;

    /**
     * Handler under test.
     */
    private AuthHandler handler;

    /**
     * API server.
     */
    private HttpServer server;

    /**
     * API client.
     */
    private WebClient client;

    @BeforeEach
    void setUp(final Vertx vertx) throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair pair = gen.generateKeyPair();
        this.logins = new FailedLogins(
            new InMemoryFailedLoginStore(), () -> new FailedLoginsConfig(0, 1, 60, 3, 0, 0, false)
        );
        this.handler = new AuthHandler(
            new JwtTokens(
                (RSAPrivateKey) pair.getPrivate(), (RSAPublicKey) pair.getPublic(),
                null, null, null
            ),
            (name, pass) -> {
                this.source.set(LoginSource.current());
                final AuthRefusal why = this.refusal.get();
                if (why != null) {
                    why.note();
                }
                return Optional.empty();
            },
            null, Policy.FREE, null, null, null,
            this.logins
        );
        final Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        this.handler.register(router);
        this.server = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1")
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        this.client = WebClient.create(vertx);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.client.close();
        this.server.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    @Test
    void lockedAccountIsAnswered403WithItsOwnCode() throws Exception {
        this.refusal.set(
            new AuthRefusal("account_locked", RsStatus.FORBIDDEN, 0L, "Account locked")
        );
        final HttpResponse<Buffer> resp = this.signIn();
        MatcherAssert.assertThat("status", resp.statusCode(), new IsEqual<>(403));
        MatcherAssert.assertThat(
            "the UI recognises the lock by this code",
            resp.bodyAsJsonObject().getString("error"), new IsEqual<>("ACCOUNT_LOCKED")
        );
    }

    @Test
    void delayedLoginIsAnswered403WithTheWait() throws Exception {
        // The same status as a lock: the code tells the two apart.
        this.refusal.set(
            new AuthRefusal("login_delayed", RsStatus.FORBIDDEN, 9L, "wait")
        );
        final HttpResponse<Buffer> resp = this.signIn();
        MatcherAssert.assertThat("status", resp.statusCode(), new IsEqual<>(403));
        MatcherAssert.assertThat(
            "the wait is in the header", resp.getHeader("Retry-After"), new IsEqual<>("9")
        );
        MatcherAssert.assertThat(
            "and the code tells the UI to show it",
            resp.bodyAsJsonObject().getString("error"), new IsEqual<>("LOGIN_DELAYED")
        );
    }

    @Test
    void outageOfTheIdentityProviderIsAnswered503() throws Exception {
        this.refusal.set(
            new AuthRefusal(
                "identity_provider_unavailable", RsStatus.SERVICE_UNAVAILABLE, 0L, "down"
            )
        );
        final HttpResponse<Buffer> resp = this.signIn();
        MatcherAssert.assertThat("status", resp.statusCode(), new IsEqual<>(503));
        MatcherAssert.assertThat(
            "code", resp.bodyAsJsonObject().getString("error"),
            new IsEqual<>("SERVICE_UNAVAILABLE")
        );
    }

    @Test
    void otherRefusalStaysTheGenericFailedSignIn() throws Exception {
        this.refusal.set(new AuthRefusal("mfa_required"));
        final HttpResponse<Buffer> resp = this.signIn();
        MatcherAssert.assertThat("status", resp.statusCode(), new IsEqual<>(401));
        MatcherAssert.assertThat(
            "the body does not say what the chain knows",
            resp.bodyAsString().contains("mfa"), new IsEqual<>(false)
        );
    }

    @Test
    void wrongPasswordIsTheGenericFailedSignIn() throws Exception {
        MatcherAssert.assertThat(
            this.signIn().statusCode(), new IsEqual<>(401)
        );
    }

    @Test
    void chainIsToldTheLoginComesFromTheSignInEndpoint() throws Exception {
        this.signIn();
        MatcherAssert.assertThat(
            "the path is not a repository's",
            this.source.get().path(), new IsEqual<>("api")
        );
        MatcherAssert.assertThat(
            "the client address is the caller's",
            this.source.get().clientIp(), new IsEqual<>("127.0.0.1")
        );
        MatcherAssert.assertThat(
            "the client program is the caller's",
            this.source.get().userAgent().isEmpty(), new IsEqual<>(false)
        );
    }

    @Test
    void browserSignInIsRecordedAsTheForm() throws Exception {
        this.client.post(this.server.actualPort(), "127.0.0.1", "/api/v1/auth/token")
            .putHeader("Origin", "http://ui.example.com")
            .sendJsonObject(new JsonObject().put("name", "alice").put("pass", "secret"))
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            this.source.get().path(), new IsEqual<>("ui")
        );
    }

    @Test
    void pausedSignInIsAnswered403WithTheWaitAndItsOwnCode() throws Exception {
        // The same status as a lock and a delay: the code tells them apart.
        this.refusal.set(
            new AuthRefusal(
                "sign_in_paused", RsStatus.FORBIDDEN, 840L,
                "Too many sign-ins without a confirmed second factor. Try again in 14 minutes."
            )
        );
        final HttpResponse<Buffer> resp = this.signIn();
        MatcherAssert.assertThat(
            "status, wait, code and text",
            java.util.List.of(
                String.valueOf(resp.statusCode()), resp.getHeader("Retry-After"),
                resp.bodyAsJsonObject().getString("error"),
                resp.bodyAsJsonObject().getString("message")
            ),
            new IsEqual<>(
                java.util.List.of(
                    "403", "840", "SIGN_IN_PAUSED",
                    "Too many sign-ins without a confirmed second factor. Try again in 14 minutes."
                )
            )
        );
    }

    @Test
    void unconfirmedSecondFactorIsTheGenericFailedSignIn() throws Exception {
        // Saying that the second factor failed would say that the password
        // was right.
        this.refusal.set(new AuthRefusal(AuthRefusal.SECOND_FACTOR_FAILED));
        final HttpResponse<Buffer> resp = this.signIn();
        MatcherAssert.assertThat("status", resp.statusCode(), new IsEqual<>(401));
        MatcherAssert.assertThat(
            "the body does not say what the chain knows",
            resp.bodyAsString().contains("factor"), new IsEqual<>(false)
        );
    }

    @Test
    void lockedUserIsToldSoAndNoProviderIsAsked(final Vertx vertx) throws Exception {
        final AtomicInteger asked = new AtomicInteger();
        final HttpServer guarded = this.serve(
            vertx,
            new LockedOut(
                new GuardedPasswords(
                    (name, pass) -> {
                        asked.incrementAndGet();
                        return Optional.empty();
                    },
                    this.logins
                ),
                this.logins
            )
        );
        try {
            for (int idx = 0; idx < 3; idx += 1) {
                this.logins.failed("alice", LoginOrigin.UNKNOWN);
            }
            final java.util.List<String> answers = new java.util.ArrayList<>(5);
            for (int idx = 0; idx < 5; idx += 1) {
                final HttpResponse<Buffer> resp = this.signIn(guarded, "Alice", "secret", "");
                answers.add(resp.statusCode() + " " + resp.bodyAsJsonObject().getString("error"));
            }
            MatcherAssert.assertThat(
                "every try is answered as locked",
                answers, new IsEqual<>(java.util.Collections.nCopies(5, "403 ACCOUNT_LOCKED"))
            );
            MatcherAssert.assertThat(
                "and no provider is asked", asked.get(), new IsEqual<>(0)
            );
        } finally {
            guarded.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void wrongPasswordsFromChangingAddressesLockTheName(final Vertx vertx) throws Exception {
        // Failed logins are counted per user name: a new client address,
        // spoofed or not, buys no new guesses.
        final HttpServer guarded = this.serve(
            vertx,
            new LockedOut(
                new GuardedPasswords(
                    (name, pass) -> {
                        final Optional<AuthUser> res;
                        if ("right".equals(pass)) {
                            res = Optional.of(new AuthUser(name, "local"));
                        } else {
                            res = Optional.empty();
                        }
                        return res;
                    },
                    this.logins
                ),
                this.logins
            )
        );
        try {
            for (int idx = 1; idx <= 3; idx += 1) {
                this.signIn(guarded, "victim", "wrong", "10.0.0." + idx);
            }
            final HttpResponse<Buffer> right = this.signIn(guarded, "victim", "right", "10.9.9.9");
            MatcherAssert.assertThat(
                "the right password from yet another address does not pass the lock",
                right.statusCode() + " " + right.bodyAsJsonObject().getString("error"),
                new IsEqual<>("403 ACCOUNT_LOCKED")
            );
        } finally {
            guarded.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void unconfirmedSecondFactorsPauseSignInAndNeverCountAsWrongPasswords(final Vertx vertx)
        throws Exception {
        final SignInFailures report = new SignInFailures(
            new InMemorySignInFailureStore(), SignInReportConfig::defaults
        );
        final FailedLogins policy = new FailedLogins(
            new InMemoryFailedLoginStore(),
            () -> new FailedLoginsConfig(2, 1, 60, 3, 0, 0, false, true, 2, 15)
        );
        policy.reportTo(report);
        final AtomicInteger asked = new AtomicInteger();
        final HttpServer guarded = this.serve(
            vertx,
            new LockedOut(
                new GuardedPasswords(
                    (name, pass) -> {
                        // The password is right; the person denies the push.
                        asked.incrementAndGet();
                        new AuthRefusal(AuthRefusal.SECOND_FACTOR_FAILED).note();
                        return Optional.empty();
                    },
                    policy
                ),
                policy
            ),
            policy
        );
        try {
            final java.util.List<String> answers = new java.util.ArrayList<>(3);
            for (int idx = 0; idx < 3; idx += 1) {
                final HttpResponse<Buffer> resp = this.client
                    .post(guarded.actualPort(), "127.0.0.1", "/api/v1/auth/token")
                    .putHeader("User-Agent", "Mozilla/5.0")
                    .putHeader("Origin", "http://ui.example.com")
                    .sendJsonObject(new JsonObject().put("name", "carol").put("pass", "secret"))
                    .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
                answers.add(resp.statusCode() + " " + resp.bodyAsJsonObject().getString("error"));
            }
            final SignInFailure line = report.list(
                new SignInFailureStore.Query(SignInFailure.SECOND_FACTOR, "carol", true, "", 10, 0)
            ).items().get(0);
            MatcherAssert.assertThat(
                "answers, pushes sent, report line, and failed logins of the name",
                java.util.List.of(
                    answers.toString(), String.valueOf(asked.get()),
                    line.hits() + " unconfirmed, " + line.refused() + " refused, "
                        + line.failures() + " counted, paused " + (line.blockedUntil() != null)
                        + ", " + line.userAgent(),
                    String.valueOf(policy.state("carol").failures())
                ),
                new IsEqual<>(
                    java.util.List.of(
                        "[401 UNAUTHORIZED, 403 SIGN_IN_PAUSED, 403 SIGN_IN_PAUSED]", "2",
                        "2 unconfirmed, 1 refused, 2 counted, paused true, Mozilla/5.0", "0"
                    )
                )
            );
        } finally {
            guarded.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void okaButtonSignsInAUserWhoIsNotLocked() {
        MatcherAssert.assertThat(
            this.handler.ssoSession("alice", "okta").accessToken().isEmpty(),
            new IsEqual<>(false)
        );
    }

    @Test
    void oktaButtonDoesNotSignInALockedUser() {
        for (int idx = 0; idx < 3; idx += 1) {
            this.logins.failed("alice", LoginOrigin.UNKNOWN);
        }
        Assertions.assertThrows(
            AuthHandler.LockedAccount.class, () -> this.handler.ssoSession("alice", "okta")
        );
    }

    private HttpResponse<Buffer> signIn() throws Exception {
        return this.client.post(this.server.actualPort(), "127.0.0.1", "/api/v1/auth/token")
            .sendJsonObject(new JsonObject().put("name", "alice").put("pass", "secret"))
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private HttpResponse<Buffer> signIn(
        final HttpServer to, final String name, final String pass, final String forwarded
    ) throws Exception {
        final io.vertx.ext.web.client.HttpRequest<Buffer> req =
            this.client.post(to.actualPort(), "127.0.0.1", "/api/v1/auth/token");
        if (!forwarded.isEmpty()) {
            req.putHeader("X-Forwarded-For", forwarded);
        }
        return req.sendJsonObject(new JsonObject().put("name", name).put("pass", pass))
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private HttpServer serve(final Vertx vertx, final Authentication chain) throws Exception {
        return this.serve(vertx, chain, this.logins);
    }

    private HttpServer serve(
        final Vertx vertx, final Authentication chain, final FailedLogins failed
    ) throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair pair = gen.generateKeyPair();
        final Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        new AuthHandler(
            new JwtTokens(
                (RSAPrivateKey) pair.getPrivate(), (RSAPublicKey) pair.getPublic(),
                null, null, null
            ),
            chain, null, Policy.FREE, null, null, null, failed
        ).register(router);
        return vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1")
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
