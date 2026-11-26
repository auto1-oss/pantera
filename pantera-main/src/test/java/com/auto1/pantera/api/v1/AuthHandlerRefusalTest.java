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
import com.auto1.pantera.auth.LoginThrottle;
import com.auto1.pantera.auth.failedlogins.FailedLogins;
import com.auto1.pantera.auth.failedlogins.InMemoryFailedLoginStore;
import com.auto1.pantera.auth.failedlogins.LoginOrigin;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthRefusal;
import com.auto1.pantera.http.auth.LoginSource;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.policy.FailedLoginsConfig;
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
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
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
 * sign-in chain knows: the account is locked, logins are delayed, the
 * identity provider cannot be reached. Everything else stays the one
 * generic message.
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
            new LoginThrottle(100, Duration.ofMinutes(15), System::nanoTime),
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
    void lockedUserIsToldSoHoweverOftenTheFormIsTried(final Vertx vertx) throws Exception {
        // The form has a limit of its own (here two tries). It must not
        // take the place of the answer that says what is wrong.
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair pair = gen.generateKeyPair();
        final java.util.concurrent.atomic.AtomicInteger asked =
            new java.util.concurrent.atomic.AtomicInteger();
        final Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        new AuthHandler(
            new JwtTokens(
                (RSAPrivateKey) pair.getPrivate(), (RSAPublicKey) pair.getPublic(),
                null, null, null
            ),
            (name, pass) -> {
                asked.incrementAndGet();
                return Optional.empty();
            },
            null, Policy.FREE, null, null, null,
            new LoginThrottle(2, Duration.ofMinutes(15), System::nanoTime),
            this.logins
        ).register(router);
        final HttpServer limited = vertx.createHttpServer().requestHandler(router)
            .listen(0, "127.0.0.1")
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        try {
            for (int idx = 0; idx < 3; idx += 1) {
                this.logins.failed("alice", LoginOrigin.UNKNOWN);
            }
            final java.util.List<String> answers = new java.util.ArrayList<>(5);
            for (int idx = 0; idx < 5; idx += 1) {
                final HttpResponse<Buffer> resp = this.client
                    .post(limited.actualPort(), "127.0.0.1", "/api/v1/auth/token")
                    .sendJsonObject(new JsonObject().put("name", "Alice").put("pass", "secret"))
                    .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
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
            limited.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void hitOfTheFormLimitGoesToTheReport(final Vertx vertx) throws Exception {
        final com.auto1.pantera.auth.signin.SignInFailures report =
            new com.auto1.pantera.auth.signin.SignInFailures(
                new com.auto1.pantera.auth.signin.InMemorySignInFailureStore(),
                com.auto1.pantera.settings.policy.SignInReportConfig::defaults
            );
        this.logins.reportTo(report);
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
            (name, pass) -> Optional.empty(),
            null, Policy.FREE, null, null, null,
            new LoginThrottle(2, Duration.ofMinutes(15), System::nanoTime),
            this.logins
        ).register(router);
        final HttpServer limited = vertx.createHttpServer().requestHandler(router)
            .listen(0, "127.0.0.1")
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        try {
            for (int idx = 0; idx < 4; idx += 1) {
                this.client.post(limited.actualPort(), "127.0.0.1", "/api/v1/auth/token")
                    .putHeader("User-Agent", "Mozilla/5.0")
                    .sendJsonObject(new JsonObject().put("name", "carol").put("pass", "secret"))
                    .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
            }
            final com.auto1.pantera.auth.signin.SignInFailure line = report.list(
                new com.auto1.pantera.auth.signin.SignInFailureStore.Query(
                    com.auto1.pantera.auth.signin.SignInFailure.FORM_LIMIT, "carol", true, "",
                    10, 0
                )
            ).items().get(0);
            MatcherAssert.assertThat(
                "the two refused tries, the failures that led to the block, and the client",
                java.util.List.of(
                    String.valueOf(line.hits()), String.valueOf(line.failures()),
                    line.userAgent(), String.valueOf(line.blockedUntil() != null)
                ),
                new IsEqual<>(java.util.List.of("2", "2", "Mozilla/5.0", "true"))
            );
        } finally {
            limited.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
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
}
