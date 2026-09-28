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
package com.auto1.pantera.nuget.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.nuget.AstoRepository;
import com.auto1.pantera.security.policy.PolicyByUsername;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.Test;

/**
 * {@code dotnet nuget push --api-key} sends the key as {@code X-NuGet-ApiKey}
 * and no {@code Authorization} header; the key is a Pantera token and must
 * authenticate the push.
 *
 * @since 2.2.9
 */
final class NuGetApiKeySliceTest {

    /**
     * Token the fake token authentication accepts.
     */
    private static final String GOOD = "good-key";

    @Test
    void liftsApiKeyIntoBearerAuthorization() {
        final AtomicReference<Headers> seen = new AtomicReference<>();
        new NuGetApiKeySlice(
            (line, headers, body) -> {
                seen.set(headers);
                return ResponseBuilder.ok().completedFuture();
            }
        ).response(
            new RequestLine(RqMethod.PUT, "/package"),
            Headers.from(new Header("X-NuGet-ApiKey", NuGetApiKeySliceTest.GOOD)),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            seen.get().values(Authorization.NAME),
            new IsEqual<>(java.util.List.of("Bearer " + NuGetApiKeySliceTest.GOOD))
        );
    }

    @Test
    void keepsExplicitAuthorization() {
        final AtomicReference<Headers> seen = new AtomicReference<>();
        final Header basic = new Authorization.Basic("alice", "secret");
        new NuGetApiKeySlice(
            (line, headers, body) -> {
                seen.set(headers);
                return ResponseBuilder.ok().completedFuture();
            }
        ).response(
            new RequestLine(RqMethod.PUT, "/package"),
            Headers.from(basic, new Header("X-NuGet-ApiKey", "other")),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            seen.get().values(Authorization.NAME),
            new IsEqual<>(java.util.List.of(basic.getValue()))
        );
    }

    @Test
    void pushWithValidApiKeyIsAuthenticated() throws Exception {
        MatcherAssert.assertThat(
            this.nuget().response(
                new RequestLine(RqMethod.PUT, "/package"),
                Headers.from(new Header("X-NuGet-ApiKey", NuGetApiKeySliceTest.GOOD)),
                Content.EMPTY
            ).join(),
            new IsNot<>(new RsHasStatus(RsStatus.UNAUTHORIZED))
        );
    }

    @Test
    void pushWithInvalidApiKeyIsRejected() throws Exception {
        MatcherAssert.assertThat(
            this.nuget().response(
                new RequestLine(RqMethod.PUT, "/package"),
                Headers.from(new Header("X-NuGet-ApiKey", "forged")),
                Content.EMPTY
            ).join(),
            new RsHasStatus(RsStatus.UNAUTHORIZED)
        );
    }

    private NuGetApiKeySlice nuget() throws Exception {
        return new NuGetApiKeySlice(
            new NuGet(
                URI.create("http://localhost").toURL(),
                new AstoRepository(new InMemoryStorage()),
                new PolicyByUsername(TestAuthentication.USERNAME),
                new TestAuthentication(),
                token -> CompletableFuture.completedFuture(
                    NuGetApiKeySliceTest.GOOD.equals(token)
                        ? Optional.of(new AuthUser(TestAuthentication.USERNAME, "test"))
                        : Optional.empty()
                ),
                "test",
                Optional.empty()
            )
        );
    }
}
