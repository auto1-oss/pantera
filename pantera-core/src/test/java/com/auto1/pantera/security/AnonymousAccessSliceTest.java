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
import com.auto1.pantera.http.rq.RequestLine;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

/**
 * Tests for {@link AnonymousAccessSlice}.
 *
 * @since 2.2.0
 */
class AnonymousAccessSliceTest {

    @Test
    void allowsAnonymousReadOnPublicProxy() {
        final Response res = serve(
            AnonymousAccessSlice.Policy.proxyDefault(),
            "GET / HTTP/1.1",
            Headers.EMPTY
        );
        MatcherAssert.assertThat(
            res.status().code(),
            new IsEqual<>(200)
        );
    }

    @Test
    void rejectsAnonymousReadOnPrivateRepo() {
        final Response res = serve(
            AnonymousAccessSlice.Policy.hostedDefault(),
            "GET / HTTP/1.1",
            Headers.EMPTY
        );
        MatcherAssert.assertThat(
            "Unauthenticated read on a private repo must be rejected with 401",
            res.status().code(),
            new IsEqual<>(401)
        );
        MatcherAssert.assertThat(
            "The 401 must carry a WWW-Authenticate challenge so clients prompt for creds",
            res.headers().find("WWW-Authenticate").isEmpty(),
            new IsEqual<>(false)
        );
    }

    @Test
    void rejectsAnonymousWriteEvenOnPublicProxy() {
        // Default proxy policy: anonymous READ allowed, anonymous
        // WRITE always denied. PUT without auth must be 401.
        final Response res = serve(
            AnonymousAccessSlice.Policy.proxyDefault(),
            "PUT /artifact.jar HTTP/1.1",
            Headers.EMPTY
        );
        MatcherAssert.assertThat(
            "Anonymous write on public proxy must still be rejected",
            res.status().code(),
            new IsEqual<>(401)
        );
    }

    @Test
    void allowsAuthorizedRequestRegardlessOfPolicy() {
        // The decorator must NOT inspect credentials — just delegate.
        // Real validation is the downstream auth slice's job.
        final Response res = serve(
            AnonymousAccessSlice.Policy.hostedDefault(),
            "GET / HTTP/1.1",
            Headers.from("Authorization", "Basic c3VwZXItbWFuOmt5cnB0b25pdGU=")
        );
        MatcherAssert.assertThat(
            "Request with Authorization header must pass through to the downstream slice",
            res.status().code(),
            new IsEqual<>(200)
        );
    }

    @Test
    void allowsAuthorizedWriteAgainstHostedRepo() {
        final Response res = serve(
            AnonymousAccessSlice.Policy.hostedDefault(),
            "PUT /artifact.jar HTTP/1.1",
            Headers.from("Authorization", "Bearer xyz")
        );
        MatcherAssert.assertThat(
            res.status().code(),
            new IsEqual<>(200)
        );
    }

    @Test
    void treatsHeadAndOptionsAsReadMethods() {
        // HEAD and OPTIONS are RFC-safe; they share the anonymous-read
        // policy. Validate both.
        final Response headRes = serve(
            AnonymousAccessSlice.Policy.proxyDefault(),
            "HEAD /artifact.jar HTTP/1.1",
            Headers.EMPTY
        );
        final Response optionsRes = serve(
            AnonymousAccessSlice.Policy.proxyDefault(),
            "OPTIONS / HTTP/1.1",
            Headers.EMPTY
        );
        MatcherAssert.assertThat(
            "HEAD must be treated as a read and allowed on public proxy",
            headRes.status().code(),
            new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "OPTIONS must be treated as a read and allowed on public proxy",
            optionsRes.status().code(),
            new IsEqual<>(200)
        );
    }

    @Test
    void treatsHeadAsReadOnPrivateRepo() {
        // HEAD on a private repo should hit the same 401 path as GET.
        final Response res = serve(
            AnonymousAccessSlice.Policy.hostedDefault(),
            "HEAD / HTTP/1.1",
            Headers.EMPTY
        );
        MatcherAssert.assertThat(
            res.status().code(),
            new IsEqual<>(401)
        );
    }

    @Test
    void caseInsensitiveAuthorizationHeader() {
        // Some clients normalise the header name to "authorization"
        // before sending — the decorator must match case-insensitively.
        final Response res = serve(
            AnonymousAccessSlice.Policy.hostedDefault(),
            "GET / HTTP/1.1",
            Headers.from("authorization", "Bearer xyz")
        );
        MatcherAssert.assertThat(
            res.status().code(),
            new IsEqual<>(200)
        );
    }

    @Test
    void defaultsAreSpecCorrect() {
        MatcherAssert.assertThat(
            "Proxy default anon-read must be true",
            AnonymousAccessSlice.Policy.proxyDefault().anonymousRead(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Proxy default anon-write must be false",
            AnonymousAccessSlice.Policy.proxyDefault().anonymousWrite(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "Hosted default anon-read must be false",
            AnonymousAccessSlice.Policy.hostedDefault().anonymousRead(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "Hosted default anon-write must be false",
            AnonymousAccessSlice.Policy.hostedDefault().anonymousWrite(),
            new IsEqual<>(false)
        );
    }

    private static Response serve(
        final AnonymousAccessSlice.Policy policy,
        final String requestLine,
        final Headers headers
    ) {
        final Slice inner = (line, hdrs, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        return new AnonymousAccessSlice(inner, policy, "test-repo")
            .response(RequestLine.from(requestLine), headers, Content.EMPTY)
            .join();
    }
}
