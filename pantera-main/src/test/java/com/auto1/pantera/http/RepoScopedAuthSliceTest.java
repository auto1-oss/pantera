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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test for {@link RepoScopedAuthSlice}: the global
 * {@code /.import} and {@code /.merge} maintenance routes must NOT be
 * reachable without a credential that holds repository-scoped WRITE on the
 * target repository named in the URL. Before 2.2.9 these routes were mounted
 * in {@code MainSlice} ahead of the authentication chain, so an anonymous or
 * bogus-{@code Authorization} request reached the importer / merge sink
 * (unauthenticated artifact overwrite, metadata merge, and the JRuby gem-path
 * RCE).
 *
 * @since 2.2.9
 */
final class RepoScopedAuthSliceTest {

    private static final Pattern IMPORT_REPO = Pattern.compile("^/\\.import/([^/]+)");

    @Test
    void anonymousRequestIsRejectedAndNeverReachesTheSink() {
        final Sink sink = new Sink();
        final RepoScopedAuthSlice slice = new RepoScopedAuthSlice(
            sink, new StubAuth(), new StubTokenAuth(), writerPolicy("alice", "repo-a"),
            IMPORT_REPO, Action.Standard.WRITE
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.PUT, "/.import/repo-a/docs/x.txt"),
            Headers.EMPTY,
            new Content.From("payload".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        ).join();
        MatcherAssert.assertThat(
            "an anonymous import must be rejected with 401",
            response.status().code(), new IsEqual<>(401)
        );
        MatcherAssert.assertThat(
            "the importer sink must never run for an anonymous request",
            sink.reached(), new IsEqual<>(false)
        );
    }

    @Test
    void bogusAuthorizationHeaderIsRejected() {
        final Sink sink = new Sink();
        final RepoScopedAuthSlice slice = new RepoScopedAuthSlice(
            sink, new StubAuth(), new StubTokenAuth(), writerPolicy("alice", "repo-a"),
            IMPORT_REPO, Action.Standard.WRITE
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.PUT, "/.import/repo-a/docs/x.txt"),
            Headers.from(new Authorization.Bearer("not-a-real-token")),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "a bogus bearer token must be rejected with 401",
            response.status().code(), new IsEqual<>(401)
        );
        MatcherAssert.assertThat(
            "the importer sink must never run for a bogus credential",
            sink.reached(), new IsEqual<>(false)
        );
    }

    @Test
    void authenticatedUserWithoutRepoWriteIsForbidden() {
        final Sink sink = new Sink();
        final RepoScopedAuthSlice slice = new RepoScopedAuthSlice(
            sink, new StubAuth(), new StubTokenAuth(), writerPolicy("alice", "other-repo"),
            IMPORT_REPO, Action.Standard.WRITE
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.PUT, "/.import/repo-a/docs/x.txt"),
            Headers.from(new Authorization.Basic("alice", "pw")),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "a writer on a DIFFERENT repo must be forbidden (403) on repo-a",
            response.status().code(), new IsEqual<>(403)
        );
        MatcherAssert.assertThat(
            "the sink must not run without repo-scoped write",
            sink.reached(), new IsEqual<>(false)
        );
    }

    @Test
    void authorizedWriterReachesTheSink() {
        final Sink sink = new Sink();
        final RepoScopedAuthSlice slice = new RepoScopedAuthSlice(
            sink, new StubAuth(), new StubTokenAuth(), writerPolicy("alice", "repo-a"),
            IMPORT_REPO, Action.Standard.WRITE
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.PUT, "/.import/repo-a/docs/x.txt"),
            Headers.from(new Authorization.Basic("alice", "pw")),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "an authorized writer must reach the importer sink",
            sink.reached(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "and receive the sink's 200",
            response.status().code(), new IsEqual<>(200)
        );
    }

    @Test
    void unparseableRepoPathFailsClosed() {
        final Sink sink = new Sink();
        final RepoScopedAuthSlice slice = new RepoScopedAuthSlice(
            sink, new StubAuth(), new StubTokenAuth(), writerPolicy("alice", "repo-a"),
            IMPORT_REPO, Action.Standard.WRITE
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.PUT, "/.import/"),
            Headers.from(new Authorization.Basic("alice", "pw")),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "a path with no resolvable repository must fail closed (404), not reach the sink",
            sink.reached(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "and must not be a 2xx",
            response.status().code() >= 400, new IsEqual<>(true)
        );
    }

    private static Policy<PermissionCollection> writerPolicy(final String user, final String repo) {
        return principal -> {
            final Permissions perms = new Permissions();
            if (user.equals(principal.name())) {
                perms.add(new AdapterBasicPermission(repo, Action.Standard.WRITE));
            }
            return perms;
        };
    }

    /**
     * Records whether the protected sink was reached.
     */
    private static final class Sink implements Slice {
        private boolean reached;

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            this.reached = true;
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        }

        boolean reached() {
            return this.reached;
        }
    }

    /**
     * Authenticates alice/pw only.
     */
    private static final class StubAuth implements Authentication {
        @Override
        public Optional<AuthUser> user(final String name, final String pass) {
            final Optional<AuthUser> result;
            if ("alice".equals(name) && "pw".equals(pass)) {
                result = Optional.of(new AuthUser(name, "test"));
            } else {
                result = Optional.empty();
            }
            return result;
        }
    }

    /**
     * Rejects every token.
     */
    private static final class StubTokenAuth implements TokenAuthentication {
        @Override
        public CompletionStage<Optional<AuthUser>> user(final String token) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }
}
