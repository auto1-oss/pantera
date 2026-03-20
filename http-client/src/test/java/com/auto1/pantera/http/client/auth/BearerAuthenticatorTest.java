/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.client.auth;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.client.FakeClientSlices;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.headers.WwwAuthenticate;
import com.auto1.pantera.http.ResponseBuilder;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Tests for {@link BearerAuthenticator}.
 *
 * @since 0.4
 */
class BearerAuthenticatorTest {

    @Test
    void shouldRequestTokenFromRealm() {
        final AtomicReference<String> pathcapture = new AtomicReference<>();
        final AtomicReference<String> querycapture = new AtomicReference<>();
        final FakeClientSlices fake = new FakeClientSlices(
            (rsline, rqheaders, rqbody) -> {
                final URI uri = rsline.uri();
                pathcapture.set(uri.getRawPath());
                querycapture.set(uri.getRawQuery());
                return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
            }
        );
        final String host = "pantera.com";
        final int port = 321;
        final String path = "/get_token";
        new BearerAuthenticator(
            fake,
            bytes -> "token",
            Authenticator.ANONYMOUS
        ).authenticate(
            Headers.from(
                new WwwAuthenticate(
                    String.format(
                        "Bearer realm=\"https://%s:%d%s\",param1=\"1\",param2=\"abc\"",
                        host, port, path
                    )
                )
            )
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "Scheme is correct",
            fake.capturedSecure(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Host is correct",
            fake.capturedHost(),
            new IsEqual<>(host)
        );
        MatcherAssert.assertThat(
            "Port is correct",
            fake.capturedPort(),
            new IsEqual<>(port)
        );
        MatcherAssert.assertThat(
            "Path is correct",
            pathcapture.get(),
            new IsEqual<>(path)
        );
        MatcherAssert.assertThat(
            "Query is correct",
            querycapture.get(),
            new IsEqual<>("param1=1&param2=abc")
        );
    }

    @Test
    void shouldPreserveCommaInScopeValue() {
        final AtomicReference<String> querycapture = new AtomicReference<>();
        final FakeClientSlices fake = new FakeClientSlices(
            (rsline, rqheaders, rqbody) -> {
                querycapture.set(rsline.uri().getRawQuery());
                return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
            }
        );
        new BearerAuthenticator(
            fake,
            bytes -> "token",
            Authenticator.ANONYMOUS
        ).authenticate(
            Headers.from(
                new WwwAuthenticate(
                    "Bearer realm=\"https://auth.docker.io/token\",scope=\"repository:library/ubuntu:pull,push\""
                )
            )
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "Scope value with comma should be preserved",
            querycapture.get(),
            new IsEqual<>("scope=repository:library/ubuntu:pull,push")
        );
    }

    @Test
    void shouldRequestTokenUsingAuthenticator() {
        final AtomicReference<Headers> capture;
        capture = new AtomicReference<>();
        final Header auth = new Header("X-Header", "Value");
        final FakeClientSlices fake = new FakeClientSlices(
            (rsline, rqheaders, rqbody) -> {
                capture.set(rqheaders);
                return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
            }
        );
        new BearerAuthenticator(
            fake,
            bytes -> "something",
            ignored -> CompletableFuture.completedFuture(Headers.from(auth))
        ).authenticate(
            Headers.from(
                new WwwAuthenticate("Bearer realm=\"https://whatever\"")
            )
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            capture.get(),
            Matchers.containsInAnyOrder(auth)
        );
    }

    @Test
    void shouldProduceBearerHeaderUsingTokenFormat() {
        final String token = "mF_9.B5f-4.1JqM";
        final byte[] response = String.format("{\"access_token\":\"%s\"}", token).getBytes();
        final AtomicReference<byte[]> captured = new AtomicReference<>();
        final Headers headers = new BearerAuthenticator(
            new FakeClientSlices(
                (rqline, rqheaders, rqbody) -> CompletableFuture.completedFuture(
                    ResponseBuilder.ok().body(response).build())
            ),
            bytes -> {
                captured.set(bytes);
                return token;
            },
            Authenticator.ANONYMOUS
        ).authenticate(
            Headers.from(new WwwAuthenticate("Bearer realm=\"http://localhost\""))
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "Token response sent to token format",
            captured.get(),
            new IsEqual<>(response)
        );
        MatcherAssert.assertThat(
            "Result headers contains authorization",
            StreamSupport.stream(
                headers.spliterator(),
                false
            ).map(Header::new).collect(Collectors.toList()),
            Matchers.contains(new Header("Authorization", String.format("Bearer %s", token)))
        );
    }
}
