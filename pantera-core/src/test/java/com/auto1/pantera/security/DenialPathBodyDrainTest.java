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
package com.auto1.pantera.security;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthScheme;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.AuthzSlice;
import com.auto1.pantera.http.auth.CombinedAuthzSlice;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;

/**
 * Exploit-regression test for the unauthenticated denial-path
 * pre-allocation (resource-dos F31).
 *
 * <p>Before 2.2.9 every auth-denial path ({@link AnonymousAccessSlice}
 * 401, {@link AuthzSlice} and {@link CombinedAuthzSlice} 403) consumed the
 * request body with {@code body.asBytesFuture()}. That materialises the
 * body through {@code Concatenation}, whose reduce seed was allocated
 * eagerly from the attacker-declared {@code Content-Length} — so an
 * unauthenticated {@code PUT} on any private repository with
 * {@code Content-Length: 2000000000} and no body at all reserved ~2 GiB
 * of heap before the 401 was even written, on a JVM that ships
 * {@code -XX:+ExitOnOutOfMemoryError}.</p>
 *
 * <p>The body must still be consumed (the reactive-body contract: an
 * unconsumed publisher leaks the Vert.x request), but it must be
 * DRAINED, never materialised. The test's body flags any
 * {@code asBytesFuture()} materialisation and records whether it was
 * subscribed at all.</p>
 *
 * @since 2.2.9
 */
final class DenialPathBodyDrainTest {

    @Test
    void anonymousDenialDrainsInsteadOfMaterialising() {
        final HugeDeclaredBody body = new HugeDeclaredBody();
        final Response res = new AnonymousAccessSlice(
            DenialPathBodyDrainTest.neverReached(),
            AnonymousAccessSlice.Policy.hostedDefault(),
            "private-repo"
        ).response(
            RequestLine.from("PUT /private-repo/x.bin HTTP/1.1"), Headers.EMPTY, body
        ).join();
        assertDrainedNotMaterialised(res, 401, body);
    }

    @Test
    void authzForbiddenDrainsInsteadOfMaterialising() {
        final HugeDeclaredBody body = new HugeDeclaredBody();
        final Response res = new AuthzSlice(
            DenialPathBodyDrainTest.neverReached(),
            DenialPathBodyDrainTest.authenticatedAs("mallory"),
            DenialPathBodyDrainTest.denyAll()
        ).response(
            RequestLine.from("PUT /repo/x.bin HTTP/1.1"),
            Headers.from("Authorization", "Basic bWFsbG9yeTpwdw=="),
            body
        ).join();
        assertDrainedNotMaterialised(res, 403, body);
    }

    @Test
    void combinedAuthzForbiddenDrainsInsteadOfMaterialising() {
        final HugeDeclaredBody body = new HugeDeclaredBody();
        final Authentication basic = (user, pass) -> Optional.of(new AuthUser(user, "test"));
        final TokenAuthentication token = tok -> CompletableFuture.completedFuture(Optional.empty());
        final Response res = new CombinedAuthzSlice(
            DenialPathBodyDrainTest.neverReached(),
            basic,
            token,
            DenialPathBodyDrainTest.denyAll()
        ).response(
            RequestLine.from("PUT /repo/x.bin HTTP/1.1"),
            Headers.from("Authorization", "Basic bWFsbG9yeTpwdw=="),
            body
        ).join();
        assertDrainedNotMaterialised(res, 403, body);
    }

    private static void assertDrainedNotMaterialised(
        final Response res, final int expected, final HugeDeclaredBody body
    ) {
        MatcherAssert.assertThat(
            "the request must be denied", res.status().code(), new IsEqual<>(expected)
        );
        MatcherAssert.assertThat(
            "the denial path must NOT materialise the body via asBytesFuture "
                + "(that pre-allocates from the attacker-declared Content-Length)",
            body.materialised(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the denial path must still consume (drain) the body so the request "
                + "publisher is not leaked",
            body.subscribed(), new IsEqual<>(true)
        );
    }

    private static Slice neverReached() {
        return (line, headers, body) -> {
            throw new AssertionError("origin must not run on a denied request");
        };
    }

    private static AuthScheme authenticatedAs(final String name) {
        return (headers, line) -> CompletableFuture.completedFuture(
            AuthScheme.result(new AuthUser(name, "test"), "")
        );
    }

    private static OperationControl denyAll() {
        final Policy<PermissionCollection> nothing = principal -> new Permissions();
        return new OperationControl(
            nothing, new AdapterBasicPermission("repo", Action.Standard.WRITE)
        );
    }

    /**
     * A body that declares ~2 GB but delivers ten real bytes, and reports
     * whether anyone tried to materialise it with {@code asBytesFuture()}.
     */
    private static final class HugeDeclaredBody implements Content {

        private static final long DECLARED = 2_000_000_000L;

        private final AtomicBoolean materialised = new AtomicBoolean();

        private final AtomicBoolean subscribed = new AtomicBoolean();

        @Override
        public Optional<Long> size() {
            return Optional.of(DECLARED);
        }

        @Override
        public CompletableFuture<byte[]> asBytesFuture() {
            this.materialised.set(true);
            return Content.super.asBytesFuture();
        }

        @Override
        public void subscribe(final Subscriber<? super ByteBuffer> subscriber) {
            this.subscribed.set(true);
            Flowable.just(
                ByteBuffer.wrap("ten  bytes".getBytes(StandardCharsets.UTF_8))
            ).subscribe(subscriber);
        }

        boolean materialised() {
            return this.materialised.get();
        }

        boolean subscribed() {
            return this.subscribed.get();
        }
    }
}
