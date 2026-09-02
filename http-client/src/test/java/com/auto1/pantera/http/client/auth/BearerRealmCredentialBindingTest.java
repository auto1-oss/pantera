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
package com.auto1.pantera.http.client.auth;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.client.FakeClientSlices;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.WwwAuthenticate;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression tests for the Bearer-challenge credential forwarding
 * SSRF: an upstream answering {@code WWW-Authenticate: Bearer realm=<url>}
 * made Pantera send the CONFIGURED upstream Basic credentials to whatever
 * host the realm named. Before 2.2.9 the realm origin was never compared to
 * the upstream the credentials belong to, so a malicious or compromised
 * upstream could harvest them by pointing the realm at itself.
 *
 * <p>The fix binds credentials to the configured upstream: the token
 * request carries them only when the realm is the same host, lives under
 * the upstream's parent domain (Docker Hub: {@code registry-1.docker.io}
 * → {@code auth.docker.io}), or is explicitly allowlisted; any other realm
 * gets an anonymous token request.</p>
 *
 * @since 2.2.9
 */
final class BearerRealmCredentialBindingTest {

    private static final String TOKEN_BODY = "{\"access_token\":\"tok\"}";

    @Test
    void credentialsAreNotForwardedToARealmOnAnotherHost() {
        final AtomicReference<Headers> seen = new AtomicReference<>();
        final Authenticator auth = GenericAuthenticator.create(
            recording(seen), URI.create("https://registry.example.com"), "bob", "12345"
        );
        auth.authenticate(
            Headers.from(new WwwAuthenticate("Bearer realm=\"https://evil.attacker.net/token\""))
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "the upstream credentials must NOT be sent to a realm on a foreign host",
            seen.get().values(Authorization.NAME).isEmpty(), new IsEqual<>(true)
        );
    }

    @Test
    void credentialsAreForwardedToARealmOnTheUpstreamHost() {
        final AtomicReference<Headers> seen = new AtomicReference<>();
        final Authenticator auth = GenericAuthenticator.create(
            recording(seen), URI.create("https://registry.example.com"), "bob", "12345"
        );
        auth.authenticate(
            Headers.from(new WwwAuthenticate("Bearer realm=\"https://registry.example.com/token\""))
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "a realm on the configured upstream host must receive the credentials",
            seen.get().values(Authorization.NAME).isEmpty(), new IsEqual<>(false)
        );
    }

    @Test
    void dockerHubStyleSiblingRealmStillReceivesCredentials() {
        final AtomicReference<Headers> seen = new AtomicReference<>();
        final Authenticator auth = GenericAuthenticator.create(
            recording(seen), URI.create("https://registry-1.docker.io"), "bob", "12345"
        );
        auth.authenticate(
            Headers.from(new WwwAuthenticate("Bearer realm=\"https://auth.docker.io/token\""))
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "a realm under the upstream's parent domain (auth.docker.io for registry-1.docker.io) must still get the credentials",
            seen.get().values(Authorization.NAME).isEmpty(), new IsEqual<>(false)
        );
    }

    @Test
    void twoLabelUpstreamDoesNotTrustEveryHostUnderItsTld() {
        final AtomicReference<Headers> seen = new AtomicReference<>();
        final Authenticator auth = GenericAuthenticator.create(
            recording(seen), URI.create("https://ghcr.io"), "bob", "12345"
        );
        auth.authenticate(
            Headers.from(new WwwAuthenticate("Bearer realm=\"https://evil.io/token\""))
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "a bare two-label upstream must not treat every *.io host as its own site",
            seen.get().values(Authorization.NAME).isEmpty(), new IsEqual<>(true)
        );
    }

    @Test
    void legacyFactoryWithoutUpstreamNeverForwardsToABearerRealm() {
        final AtomicReference<Headers> seen = new AtomicReference<>();
        final Authenticator auth = GenericAuthenticator.create(recording(seen), "bob", "12345");
        auth.authenticate(
            Headers.from(new WwwAuthenticate("Bearer realm=\"https://anything.example/token\""))
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "with no known upstream the safe default is an anonymous token request",
            seen.get().values(Authorization.NAME).isEmpty(), new IsEqual<>(true)
        );
    }

    private static FakeClientSlices recording(final AtomicReference<Headers> seen) {
        return new FakeClientSlices(
            (line, headers, body) -> {
                seen.set(headers);
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok().jsonBody(TOKEN_BODY).build()
                );
            }
        );
    }
}
