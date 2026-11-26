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
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;

/**
 * The sign-in chain runs under the logging context of the login it checks:
 * the user name being checked and the client address and trace of that
 * request. What an earlier request left on a pooled thread must not name
 * another user on the records the chain writes.
 */
final class PasswordCheckLogContextTest {

    /**
     * A credential with the form of an API key.
     */
    private static final String KEY =
        "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJl";

    /**
     * Context the chain saw while it checked the login.
     */
    private final AtomicReference<Map<String, String>> seen = new AtomicReference<>();

    /**
     * A thread that holds the context of another user's request.
     */
    private ExecutorService polluted;

    @BeforeEach
    void setUp() throws Exception {
        MDC.clear();
        this.polluted = Executors.newSingleThreadExecutor();
        this.polluted.submit(PasswordCheckLogContextTest::pollute).get();
    }

    @AfterEach
    void tearDown() {
        this.polluted.shutdownNow();
        MDC.clear();
    }

    @Test
    void chainRunsUnderTheContextOfItsOwnLogin() {
        PasswordCheckLogContextTest.pollute();
        new PasswordCheck(this.chain(), PasswordCheckLogContextTest.request("secret"))
            .now("alice", "secret");
        MatcherAssert.assertThat(
            this.seen.get(), new IsEqual<>(PasswordCheckLogContextTest.own())
        );
    }

    @Test
    void threadGetsItsContextBackAfterTheCheck() {
        PasswordCheckLogContextTest.pollute();
        final Map<String, String> before = MDC.getCopyOfContextMap();
        new PasswordCheck(this.chain(), PasswordCheckLogContextTest.request("secret"))
            .now("alice", "secret");
        MatcherAssert.assertThat(
            MDC.getCopyOfContextMap(), new IsEqual<>(before)
        );
    }

    @Test
    void threadGetsItsContextBackWhenTheChainThrows() {
        PasswordCheckLogContextTest.pollute();
        final Map<String, String> before = MDC.getCopyOfContextMap();
        final PasswordCheck check = new PasswordCheck(
            (name, pass) -> {
                throw new IllegalStateException("provider failed");
            },
            PasswordCheckLogContextTest.request("secret")
        );
        Assertions.assertThrows(
            IllegalStateException.class, () -> check.now("alice", "secret")
        );
        MatcherAssert.assertThat(
            MDC.getCopyOfContextMap(), new IsEqual<>(before)
        );
    }

    @Test
    void checkWithoutRequestNamesTheUserBeingChecked() {
        MDC.put(EcsMdc.USER_NAME, "mallory");
        new PasswordCheck(this.chain()).now("alice", "secret");
        MatcherAssert.assertThat(
            this.seen.get().get(EcsMdc.USER_NAME), new IsEqual<>("alice")
        );
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    @Timeout(30)
    void passwordIsCheckedUnderItsOwnContextThroughEverySlice(final Kind kind) {
        PasswordCheckLogContextTest.pollute();
        kind.slice(this.chain(), token -> CompletableFuture.completedFuture(Optional.empty()))
            .response(
                new RequestLine("GET", "/any"),
                PasswordCheckLogContextTest.request("wrong password"), Content.EMPTY
            ).join();
        MatcherAssert.assertThat(
            this.seen.get(), new IsEqual<>(PasswordCheckLogContextTest.own())
        );
    }

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"COMBINED_SCHEME", "COMBINED_SLICE"})
    @Timeout(30)
    void rejectedKeyIsCheckedUnderItsOwnContext(final Kind kind) throws Exception {
        // The token check ends on a thread that still holds another user's
        // context, and the password chain is started from there.
        final CountDownLatch asked = new CountDownLatch(1);
        final CompletableFuture<?> answer = kind.slice(
            this.chain(),
            token -> CompletableFuture.supplyAsync(
                () -> {
                    PasswordCheckLogContextTest.await(asked);
                    return Optional.<AuthUser>empty();
                },
                this.polluted
            )
        ).response(
            new RequestLine("GET", "/any"),
            PasswordCheckLogContextTest.request(PasswordCheckLogContextTest.KEY),
            Content.EMPTY
        );
        asked.countDown();
        answer.get(20, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            this.seen.get(), new IsEqual<>(PasswordCheckLogContextTest.own())
        );
    }

    /**
     * Sign-in chain that records the logging context it runs under.
     * @return Chain that refuses every login
     */
    private Authentication chain() {
        return (name, pass) -> {
            final Map<String, String> ctx = MDC.getCopyOfContextMap();
            this.seen.set(ctx == null ? Map.of() : new HashMap<>(ctx));
            return Optional.empty();
        };
    }

    /**
     * Put the context of another user's request on the current thread.
     */
    private static void pollute() {
        MDC.put(EcsMdc.USER_NAME, "mallory");
        MDC.put(EcsMdc.CLIENT_IP, "198.51.100.9");
        MDC.put(EcsMdc.TRACE_ID, "trace-of-mallory");
        MDC.put(EcsMdc.REPO_NAME, "repo-of-mallory");
    }

    /**
     * Context of alice's login.
     * @return The only keys the chain may see
     */
    private static Map<String, String> own() {
        return Map.of(
            EcsMdc.USER_NAME, "alice",
            EcsMdc.CLIENT_IP, "192.0.2.7",
            EcsMdc.TRACE_ID, "trace-of-alice"
        );
    }

    /**
     * Headers of alice's request, as the access-log slice stamps them.
     * @param password Basic password
     * @return Headers
     */
    private static Headers request(final String password) {
        return Headers.from(new Authorization.Basic("alice", password))
            .add(new Header(EcsLoggingSlice.CTX_TRACE_ID_HEADER, "trace-of-alice"))
            .add(new Header(EcsLoggingSlice.CTX_CLIENT_IP_HEADER, "192.0.2.7"));
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The three slices that check a Basic password.
     */
    enum Kind {

        /**
         * Basic scheme.
         */
        BASIC {
            @Override
            Slice slice(final Authentication chain, final TokenAuthentication tokens) {
                return new BasicAuthzSlice(
                    new SliceSimple(ResponseBuilder.ok().build()), chain,
                    PasswordCheckLogContextTest.control()
                );
            }
        },

        /**
         * Basic or Bearer scheme behind the common authorization slice.
         */
        COMBINED_SCHEME {
            @Override
            Slice slice(final Authentication chain, final TokenAuthentication tokens) {
                return new AuthzSlice(
                    new SliceSimple(ResponseBuilder.ok().build()),
                    new CombinedAuthScheme(chain, tokens),
                    PasswordCheckLogContextTest.control()
                );
            }
        },

        /**
         * Combined authorization slice.
         */
        COMBINED_SLICE {
            @Override
            Slice slice(final Authentication chain, final TokenAuthentication tokens) {
                return new CombinedAuthzSlice(
                    new SliceSimple(ResponseBuilder.ok().build()), chain, tokens,
                    PasswordCheckLogContextTest.control()
                );
            }
        };

        /**
         * Slice of this kind.
         * @param chain Sign-in chain
         * @param tokens Token check
         * @return The slice
         */
        abstract Slice slice(Authentication chain, TokenAuthentication tokens);
    }

    private static OperationControl control() {
        return new OperationControl(
            Policy.FREE, new AdapterBasicPermission("any_repo_name", Action.ALL)
        );
    }
}
