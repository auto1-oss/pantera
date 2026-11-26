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
import java.util.concurrent.CompletionStage;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A token that is refused for a reason the token check knows (its owner is
 * locked) is answered with that reason, through each of the three slices
 * that check a Bearer token.
 */
final class TokenRefusalSliceTest {

    @ParameterizedTest
    @EnumSource(Kind.class)
    void tokenOfALockedUserIsAnswered403(final Kind kind) {
        final Answer answer = TokenRefusalSliceTest.ask(kind, new Locked());
        MatcherAssert.assertThat(
            "the refusal's status is the answer",
            answer.response.status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "the body says why",
            new String(answer.response.body().asBytes(), StandardCharsets.UTF_8),
            new IsEqual<>("Account locked")
        );
        MatcherAssert.assertThat(
            "a 403 asks for no credentials",
            answer.response.headers().values("WWW-Authenticate"), new IsEqual<>(List.of())
        );
        MatcherAssert.assertThat(
            "the reason is noted for the access record",
            answer.reason, new IsEqual<>(Optional.of("account_locked"))
        );
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void rejectedTokenWithoutReasonStaysA401(final Kind kind) {
        final Answer answer = TokenRefusalSliceTest.ask(
            kind, token -> CompletableFuture.completedFuture(Optional.empty())
        );
        MatcherAssert.assertThat(
            "an invalid token is a 401",
            answer.response.status(), new IsEqual<>(RsStatus.UNAUTHORIZED)
        );
        MatcherAssert.assertThat(
            "with no reason from the sign-in code",
            answer.reason, new IsEqual<>(Optional.empty())
        );
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void acceptedTokenIsServed(final Kind kind) {
        final Answer answer = TokenRefusalSliceTest.ask(
            kind,
            token -> CompletableFuture.completedFuture(Optional.of(new AuthUser("alice", "test")))
        );
        MatcherAssert.assertThat(
            answer.response.status(), new IsEqual<>(RsStatus.OK)
        );
    }

    private static Answer ask(final Kind kind, final TokenAuthentication tokens) {
        final String slot = RequestReasons.instance().open();
        final Response response = kind.slice(tokens).response(
            new RequestLine("GET", "/any"),
            Headers.from(new Authorization.Bearer("any-token"))
                .add(new Header(RequestReasons.SLOT_HEADER, slot)),
            Content.EMPTY
        ).join();
        return new Answer(response, RequestReasons.instance().close(slot));
    }

    /**
     * Token check of an installation in which the owner of every token is
     * locked.
     */
    private static final class Locked implements TokenAuthentication {

        @Override
        public CompletionStage<Optional<AuthUser>> user(final String token) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletionStage<PasswordCheck.Outcome> check(final String token) {
            return CompletableFuture.completedFuture(
                new PasswordCheck.Outcome(
                    Optional.empty(),
                    Optional.of(
                        new AuthRefusal(
                            "account_locked", RsStatus.FORBIDDEN, 0L, "Account locked"
                        )
                    )
                )
            );
        }
    }

    /**
     * The three slices that check a Bearer token.
     */
    enum Kind {

        /**
         * Bearer scheme.
         */
        BEARER {
            @Override
            Slice slice(final TokenAuthentication tokens) {
                return new BearerAuthzSlice(
                    new SliceSimple(ResponseBuilder.ok().build()), tokens,
                    TokenRefusalSliceTest.control()
                );
            }
        },

        /**
         * Basic or Bearer scheme behind the common authorization slice.
         */
        COMBINED_SCHEME {
            @Override
            Slice slice(final TokenAuthentication tokens) {
                return new AuthzSlice(
                    new SliceSimple(ResponseBuilder.ok().build()),
                    new CombinedAuthScheme((name, pass) -> Optional.empty(), tokens),
                    TokenRefusalSliceTest.control()
                );
            }
        },

        /**
         * Combined authorization slice.
         */
        COMBINED_SLICE {
            @Override
            Slice slice(final TokenAuthentication tokens) {
                return new CombinedAuthzSlice(
                    new SliceSimple(ResponseBuilder.ok().build()),
                    (name, pass) -> Optional.empty(), tokens,
                    TokenRefusalSliceTest.control()
                );
            }
        };

        /**
         * Slice of this kind.
         * @param tokens Token check
         * @return The slice
         */
        abstract Slice slice(TokenAuthentication tokens);
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
