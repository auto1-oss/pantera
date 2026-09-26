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
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * {@code pantera_login} names the authenticated principal for every
 * downstream consumer (audit {@code user.name}, artifact owner, import
 * caller). A client that sends its own {@code pantera_login} header must
 * not be able to put a name of its choosing there.
 */
final class LoginHeaderSpoofTest {

    @BeforeEach
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void basicAuthzSliceReplacesClientSentLogin() {
        final AtomicReference<Headers> seen = new AtomicReference<>();
        new BasicAuthzSlice(
            LoginHeaderSpoofTest.recording(seen),
            (usr, pwd) -> Optional.of(new AuthUser("alice", "test")),
            LoginHeaderSpoofTest.control()
        ).response(
            new RequestLine("PUT", "/a.txt"),
            Headers.from(new Authorization.Basic("alice", "pwd"))
                .add("Pantera_Login", "victim"),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            seen.get().values(AuthzSlice.LOGIN_HDR), new IsEqual<>(List.of("alice"))
        );
    }

    @Test
    void combinedAuthzSliceReplacesClientSentLogin() {
        final AtomicReference<Headers> seen = new AtomicReference<>();
        new CombinedAuthzSlice(
            LoginHeaderSpoofTest.recording(seen),
            (usr, pwd) -> Optional.empty(),
            token -> CompletableFuture.completedFuture(
                Optional.of(new AuthUser("alice", "test"))
            ),
            LoginHeaderSpoofTest.control()
        ).response(
            new RequestLine("PUT", "/a.txt"),
            Headers.from(new Authorization.Bearer("tkn"))
                .add(AuthzSlice.LOGIN_HDR, "victim"),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            seen.get().values(AuthzSlice.LOGIN_HDR), new IsEqual<>(List.of("alice"))
        );
    }

    @Test
    void entryDropsClientSentLogin() {
        // Anonymous-allowed routes never pass an authorization slice, so the
        // entry point must not forward a client-sent principal either.
        final AtomicReference<Headers> seen = new AtomicReference<>();
        new EcsLoggingSlice(LoginHeaderSpoofTest.recording(seen), "10.0.0.7").response(
            new RequestLine("GET", "/a.txt"),
            Headers.from(AuthzSlice.LOGIN_HDR, "victim"),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            new Login(seen.get()).getValue(), new IsEqual<>("UNKNOWN")
        );
    }

    private static Slice recording(final AtomicReference<Headers> seen) {
        return (line, headers, body) -> {
            seen.set(headers);
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        };
    }

    private static OperationControl control() {
        return new OperationControl(
            Policy.FREE, new AdapterBasicPermission("repo", Action.ALL)
        );
    }
}
