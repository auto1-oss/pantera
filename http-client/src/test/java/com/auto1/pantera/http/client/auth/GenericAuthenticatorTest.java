/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.client.auth;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.client.FakeClientSlices;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.WwwAuthenticate;
import com.auto1.pantera.http.ResponseBuilder;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Tests for {@link GenericAuthenticator}.
 */
class GenericAuthenticatorTest {

    @Test
    void shouldProduceNothingWhenNoAuthRequested() {
        MatcherAssert.assertThat(
            new GenericAuthenticator(
                new FakeClientSlices((line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.ok().build())),
                "alice",
                "qwerty"
            ).authenticate(Headers.EMPTY).toCompletableFuture().join(),
            new IsEqual<>(Headers.EMPTY)
        );
    }

    @Test
    void shouldProduceBasicHeaderWhenRequested() {
        MatcherAssert.assertThat(
            StreamSupport.stream(
                new GenericAuthenticator(
                    new FakeClientSlices((line, headers, body) -> CompletableFuture.completedFuture(
                        ResponseBuilder.ok().build())),
                    "Aladdin",
                    "open sesame"
                ).authenticate(
                    Headers.from(new WwwAuthenticate("Basic"))
                ).toCompletableFuture().join().spliterator(),
                false
            ).map(Map.Entry::getKey).collect(Collectors.toList()),
            Matchers.contains(Authorization.NAME)
        );
    }

    @Test
    void shouldProduceBearerHeaderWhenRequested() {
        MatcherAssert.assertThat(
            StreamSupport.stream(
                new GenericAuthenticator(
                    new FakeClientSlices(
                        (line, headers, body) -> CompletableFuture.completedFuture(ResponseBuilder.ok()
                            .jsonBody("{\"access_token\":\"mF_9.B5f-4.1JqM\"}")
                            .build())
                    ),
                    "bob",
                    "12345"
                ).authenticate(
                    Headers.from(new WwwAuthenticate("Bearer realm=\"https://pantera.com\""))
                ).toCompletableFuture().join().spliterator(),
                false
            ).map(Map.Entry::getKey).collect(Collectors.toList()),
            Matchers.contains(Authorization.NAME)
        );
    }

    @Test
    void shouldPreferBearerWhenBothSchemesPresentWithoutCredentials() {
        final FakeClientSlices slices = new FakeClientSlices(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .jsonBody("{\"access_token\":\"anon-token\"}")
                    .build()
            )
        );
        final Headers challenges = new Headers()
            .add(WwwAuthenticate.NAME, "Basic realm=\"registry\"")
            .add(WwwAuthenticate.NAME,
                "Bearer realm=\"https://auth.docker.io/token\",service=\"registry.docker.io\",scope=\"repository:library/nginx:pull\"")
        ;
        final Headers headers = GenericAuthenticator.create(slices, null, null)
            .authenticate(challenges)
            .toCompletableFuture().join();
        MatcherAssert.assertThat(
            headers.single(Authorization.NAME).getValue(),
            Matchers.startsWith("Bearer ")
        );
    }
}
