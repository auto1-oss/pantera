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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.CombinedAuthzSliceWrap;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.PolicyByUsername;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test: {@link BrowsableSlice} must never infer that a
 * request is authorized merely because the wrapped origin answered 404.
 *
 * <p>Before 2.2.9, for a directory-looking HTML request the slice called
 * the origin, and on a 404 served a directory listing on the assumption
 * that "auth has already passed". But a 404 is exactly what an
 * UNAUTHENTICATED request gets from an adapter route table when the path
 * matches no rule ({@code SliceRoute} falls through to {@code notFound()}
 * before any per-route auth slice runs) — e.g. a traversal path on a
 * Composer/NuGet repo — so a bogus {@code Authorization} header plus an
 * unmatched path yielded a directory listing of a private repository.</p>
 *
 * @since 2.2.9
 */
final class BrowsableSlice404AuthorizationTest {

    @Test
    void originNotFoundDoesNotAuthorizeADirectoryListing() throws Exception {
        final Storage storage = new InMemoryStorage();
        storage.save(
            new Key.From("secret-dir/file.txt"),
            new Content.From("x".getBytes(StandardCharsets.UTF_8))
        ).join();
        // Origin that never authenticated anything — it just could not match
        // the path, the way an adapter route table answers an unmatched
        // request before any auth slice is consulted.
        final Slice unmatched = (line, headers, body) ->
            ResponseBuilder.notFound().completedFuture();
        final Response response = new BrowsableSlice(unmatched, storage).response(
            new RequestLine(RqMethod.GET, "/secret-dir/"),
            Headers.from(
                new Authorization.Bearer("garbage"),
                new Header("Accept", "text/html")
            ),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "a 404 from an unauthenticated origin must not unlock a directory "
                + "listing for a request carrying a bogus credential",
            response.status().code() == 200, new IsEqual<>(false)
        );
    }

    @Test
    void gatedListingValidatesTheCredentialItself() throws Exception {
        final Storage storage = new InMemoryStorage();
        storage.save(
            new Key.From("secret-dir/file.txt"),
            new Content.From("x".getBytes(StandardCharsets.UTF_8))
        ).join();
        final Slice unmatched = (line, headers, body) ->
            ResponseBuilder.notFound().completedFuture();
        // The production gate: Basic auth that knows only alice, token auth
        // that knows no tokens, and a policy that grants alice READ.
        final Authentication basic = (user, pwd) ->
            "alice".equals(user) && "secret".equals(pwd)
                ? Optional.of(new AuthUser("alice", "test")) : Optional.empty();
        final java.util.function.UnaryOperator<Slice> gate = browse ->
            new CombinedAuthzSliceWrap(
                browse,
                basic,
                token -> CompletableFuture.completedFuture(Optional.<AuthUser>empty()),
                new OperationControl(
                    new PolicyByUsername("alice"),
                    new AdapterBasicPermission("repo", Action.Standard.READ)
                )
            );
        final BrowsableSlice slice = new BrowsableSlice(unmatched, storage, gate);
        final Headers html = Headers.from(new Header("Accept", "text/html"));
        final Response bogus = slice.response(
            new RequestLine(RqMethod.GET, "/secret-dir/"),
            html.copy().add(new Authorization.Bearer("garbage")),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "through the gate a bogus credential is rejected with 401",
            bogus.status().code(), new IsEqual<>(401)
        );
        final Response alice = slice.response(
            new RequestLine(RqMethod.GET, "/secret-dir/"),
            html.copy().add(new Authorization.Basic("alice", "secret")),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "through the gate a valid reader still gets the directory listing",
            alice.status().code(), new IsEqual<>(200)
        );
    }
}
