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
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.RequestReasons;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A refusal that the sign-in chain notes reaches the client and the access
 * record, through each of the three slices that check a Basic password.
 */
final class AuthRefusalSliceTest {

    /**
     * A password that has not the form of an API key.
     */
    private static final String PASSWORD = "wrong password";

    @ParameterizedTest
    @EnumSource(Kind.class)
    void refusalReasonIsNotedForTheAccessRecord(final Kind kind) {
        final Answer answer = AuthRefusalSliceTest.ask(
            kind,
            (name, pass) -> {
                new AuthRefusal("mfa_required").note();
                return Optional.empty();
            }
        );
        MatcherAssert.assertThat(
            "a refusal without a status of its own is a 401",
            answer.response.status(), new IsEqual<>(RsStatus.UNAUTHORIZED)
        );
        MatcherAssert.assertThat(
            "the 401 still carries the challenge",
            answer.response.headers().values("WWW-Authenticate").isEmpty(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the reason is noted for the access record",
            answer.reason, new IsEqual<>(Optional.of("mfa_required"))
        );
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void lockedAccountIsAnswered403WithoutChallenge(final Kind kind) {
        final Answer answer = AuthRefusalSliceTest.ask(
            kind,
            (name, pass) -> {
                new AuthRefusal(
                    "account_locked", RsStatus.FORBIDDEN, 0L, "Account locked"
                ).note();
                return Optional.empty();
            }
        );
        MatcherAssert.assertThat(
            "the refusal's status is the answer",
            answer.response.status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "a 403 asks for no credentials",
            answer.response.headers().values("WWW-Authenticate"),
            new IsEqual<>(List.of())
        );
        MatcherAssert.assertThat(
            "the body says why",
            new String(answer.response.body().asBytes(), StandardCharsets.UTF_8),
            new IsEqual<>("Account locked")
        );
        MatcherAssert.assertThat(
            "the reason is noted for the access record",
            answer.reason, new IsEqual<>(Optional.of("account_locked"))
        );
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void delayedLoginIsAnswered429WithRetryAfter(final Kind kind) {
        final Answer answer = AuthRefusalSliceTest.ask(
            kind,
            (name, pass) -> {
                new AuthRefusal(
                    "login_delayed", RsStatus.TOO_MANY_REQUESTS, 9L, "Try again in 9 seconds"
                ).note();
                return Optional.empty();
            }
        );
        MatcherAssert.assertThat(
            "the refusal's status is the answer",
            answer.response.status(), new IsEqual<>(RsStatus.TOO_MANY_REQUESTS)
        );
        MatcherAssert.assertThat(
            "the client is told when to try again",
            answer.response.headers().values("Retry-After"),
            new IsEqual<>(List.of("9"))
        );
        MatcherAssert.assertThat(
            "the reason is noted for the access record",
            answer.reason, new IsEqual<>(Optional.of("login_delayed"))
        );
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void acceptedLoginCarriesNoRefusal(final Kind kind) {
        final Answer answer = AuthRefusalSliceTest.ask(
            kind,
            (name, pass) -> {
                new AuthRefusal("mfa_required").note();
                return Optional.of(new AuthUser(name, "test"));
            }
        );
        MatcherAssert.assertThat(
            "the request is served",
            answer.response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "no reason is noted",
            answer.reason, new IsEqual<>(Optional.empty())
        );
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void plainFailedLoginStaysA401WithoutReason(final Kind kind) {
        final Answer answer = AuthRefusalSliceTest.ask(
            kind, (name, pass) -> Optional.empty()
        );
        MatcherAssert.assertThat(
            "wrong credentials are a 401",
            answer.response.status(), new IsEqual<>(RsStatus.UNAUTHORIZED)
        );
        MatcherAssert.assertThat(
            "the sign-in code notes no reason of its own",
            answer.reason, new IsEqual<>(Optional.empty())
        );
    }

    /**
     * Send one request with a Basic password through a slice of the kind.
     * @param kind Slice kind
     * @param chain Sign-in chain
     * @return The answer and the reason noted for it
     */
    private static Answer ask(final Kind kind, final Authentication chain) {
        final String slot = RequestReasons.instance().open();
        final Response response = kind.slice(chain).response(
            new RequestLine("GET", "/any"),
            Headers.from(new Authorization.Basic("alice", AuthRefusalSliceTest.PASSWORD))
                .add(new Header(RequestReasons.SLOT_HEADER, slot)),
            Content.EMPTY
        ).join();
        return new Answer(response, RequestReasons.instance().close(slot));
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
            Slice slice(final Authentication chain) {
                return new BasicAuthzSlice(
                    AuthRefusalSliceTest.origin(), chain, AuthRefusalSliceTest.control()
                );
            }
        },

        /**
         * Basic or Bearer scheme behind the common authorization slice.
         */
        COMBINED_SCHEME {
            @Override
            Slice slice(final Authentication chain) {
                return new AuthzSlice(
                    AuthRefusalSliceTest.origin(),
                    new CombinedAuthScheme(
                        chain, token -> CompletableFuture.completedFuture(Optional.empty())
                    ),
                    AuthRefusalSliceTest.control()
                );
            }
        },

        /**
         * Combined authorization slice.
         */
        COMBINED_SLICE {
            @Override
            Slice slice(final Authentication chain) {
                return new CombinedAuthzSlice(
                    AuthRefusalSliceTest.origin(), chain,
                    token -> CompletableFuture.completedFuture(Optional.empty()),
                    AuthRefusalSliceTest.control()
                );
            }
        };

        /**
         * Slice of this kind.
         * @param chain Sign-in chain
         * @return The slice
         */
        abstract Slice slice(Authentication chain);
    }

    private static Slice origin() {
        return new SliceSimple(ResponseBuilder.ok().build());
    }

    private static OperationControl control() {
        return new OperationControl(
            Policy.FREE, new AdapterBasicPermission("any_repo_name", Action.ALL)
        );
    }

    /**
     * Answer of a slice with the reason noted for the access record.
     * @param response Response
     * @param reason Reason, if one was noted
     */
    private record Answer(Response response, Optional<String> reason) {
    }
}
