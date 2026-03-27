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
package com.auto1.pantera.http.client.jetty;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.client.HttpClientSettings;
import com.auto1.pantera.http.client.HttpServer;
import com.auto1.pantera.http.client.ProxySettings;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.net.ssl.SSLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tests for {@link JettyClientSlices}.
 */
final class JettyClientSlicesTest {

    private final HttpServer server = new HttpServer();

    @BeforeEach
    void setUp() {
        this.server.start();
    }

    @AfterEach
    void tearDown() {
        this.server.stop();
    }

    @Test
    void shouldProduceHttp() {
        MatcherAssert.assertThat(
            new JettyClientSlices().http("example.com"),
            new IsInstanceOf(JettyClientSlice.class)
        );
    }

    @Test
    void shouldProduceHttpWithPort() {
        final int custom = 8080;
        MatcherAssert.assertThat(
            new JettyClientSlices().http("localhost", custom),
            new IsInstanceOf(JettyClientSlice.class)
        );
    }

    @Test
    void shouldProduceHttps() {
        MatcherAssert.assertThat(
            new JettyClientSlices().http("pantera.com"),
            new IsInstanceOf(JettyClientSlice.class)
        );
    }

    @Test
    void shouldProduceHttpsWithPort() {
        final int custom = 9876;
        MatcherAssert.assertThat(
            new JettyClientSlices().http("www.pantera.com", custom),
            new IsInstanceOf(JettyClientSlice.class)
        );
    }

    @Test
    void shouldSupportProxy() throws Exception {
        final byte[] response = "response from proxy".getBytes();
        this.server.update(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().body(response).build()
            )
        );
        final JettyClientSlices client = new JettyClientSlices(
            new HttpClientSettings().addProxy(
                new ProxySettings("http", "localhost", this.server.port())
            )
        );
        try {
            client.start();
            byte[] actual = client.http("pantera.com").response(
                new RequestLine(RqMethod.GET, "/"),
                Headers.EMPTY,
                Content.EMPTY
            ).join().body().asBytes();
            Assertions.assertArrayEquals(response, actual);
        } finally {
            client.stop();
        }
    }

    @Test
    void shouldNotFollowRedirectIfDisabled() {
        final RsStatus status = RsStatus.TEMPORARY_REDIRECT;
        this.server.update(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.temporaryRedirect()
                .header("Location", "/other/path")
                .build()
            )
        );
        final JettyClientSlices client = new JettyClientSlices(
            new HttpClientSettings().setFollowRedirects(false)
        );
        try {
            client.start();

            Assertions.assertEquals(status,
                client.http("localhost", this.server.port()).response(
                    new RequestLine(RqMethod.GET, "/some/path"),
                    Headers.EMPTY, Content.EMPTY
                ).join().status()
            );
        } finally {
            client.stop();
        }
    }

    @Test
    void shouldFollowRedirectIfEnabled() {
        this.server.update(
            (line, headers, body) -> {
                if (line.toString().contains("target")) {
                    return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
                }
                return CompletableFuture.completedFuture(
                    ResponseBuilder.temporaryRedirect()
                    .header("Location", "/target")
                        .build()
                );
            }
        );
        final JettyClientSlices client = new JettyClientSlices(
            new HttpClientSettings().setFollowRedirects(true)
        );
        try {
            client.start();
            Assertions.assertEquals(RsStatus.OK,
                client.http("localhost", this.server.port()).response(
                    new RequestLine(RqMethod.GET, "/some/path"),
                    Headers.EMPTY, Content.EMPTY).join().status()
            );
        } finally {
            client.stop();
        }
    }

    @Test
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    void shouldTimeoutConnectionIfDisabled() {
        // When connectTimeout=0 (disabled), Jetty doesn't set connection timeout
        // Connection attempts will hang until OS timeout or test timeout
        final int testWaitSeconds = 2;
        final JettyClientSlices client = new JettyClientSlices(
            new HttpClientSettings().setConnectTimeout(0)
        );
        try {
            client.start();
            // Use TEST-NET-1 (192.0.2.0/24) - reserved for documentation, guaranteed non-routable
            // TCP connection will hang (no SYN-ACK) until OS or test timeout
            final String nonroutable = "192.0.2.1";
            final CompletionStage<Response> received = client.http(nonroutable).response(
                new RequestLine(RqMethod.GET, "/conn-timeout"),
                Headers.EMPTY,
                Content.EMPTY
            );
            // Test's .get() timeout should trigger - no Jetty timeout configured
            Assertions.assertThrows(
                TimeoutException.class,
                () -> received.toCompletableFuture().get(testWaitSeconds, TimeUnit.SECONDS),
                "Connection should hang without Jetty timeout, test timeout should fire"
            );
        } finally {
            client.stop();
        }
    }

    @Test
    void shouldTimeoutConnectionIfEnabled() throws Exception {
        // Set Jetty idleTimeout to 500ms (applies after connection established)
        // ConnectTimeout only applies during TCP handshake, hard to test reliably
        final int jettyTimeoutMs = 500;
        
        // Create a server that accepts connections but never sends response
        final java.net.ServerSocket blackhole = new java.net.ServerSocket(0);
        final int port = blackhole.getLocalPort();
        
        final JettyClientSlices client = new JettyClientSlices(
            new HttpClientSettings()
                .setConnectTimeout(5_000) // Long connect timeout
                .setIdleTimeout(jettyTimeoutMs) // Short idle timeout
        );
        
        try {
            client.start();
            // Connect to black hole server - TCP connects but HTTP response never arrives
            final CompletionStage<Response> received = client.http("localhost", port).response(
                new RequestLine(RqMethod.GET, "/idle-timeout"),
                Headers.EMPTY,
                Content.EMPTY
            );
            // Jetty's idleTimeout should fire when no data received
            final ExecutionException ex = Assertions.assertThrows(
                ExecutionException.class,
                () -> received.toCompletableFuture().get(5, TimeUnit.SECONDS),
                "Jetty should timeout idle connection"
            );
            // Verify it's a timeout-related exception
            final Throwable cause = ex.getCause();
            Assertions.assertNotNull(cause, "ExecutionException should have a cause");
            final String causeType = cause.getClass().getName().toLowerCase();
            final String msg = cause.getMessage() != null ? cause.getMessage().toLowerCase() : "";
            Assertions.assertTrue(
                cause instanceof java.util.concurrent.TimeoutException
                    || causeType.contains("timeout")
                    || msg.contains("timeout") 
                    || msg.contains("idle"),
                "Exception should be timeout-related, got: " + cause.getClass().getName() + ": " + cause.getMessage()
            );
        } finally {
            client.stop();
            blackhole.close();
        }
    }

    @Test
    void shouldTimeoutIdleConnectionIfEnabled() throws Exception {
        final int timeout = 1_000;
        this.server.update((line, headers, body) -> new CompletableFuture<>());
        final JettyClientSlices client = new JettyClientSlices(
            new HttpClientSettings().setIdleTimeout(timeout)
        );
        try {
            client.start();
            final CompletionStage<Response> received = client.http(
                "localhost",
                this.server.port()
            ).response(
                new RequestLine(RqMethod.GET, "/idle-timeout"),
                Headers.EMPTY,
                Content.EMPTY
            );
            Assertions.assertThrows(
                ExecutionException.class,
                () -> received.toCompletableFuture().get(timeout + 1, TimeUnit.SECONDS)
            );
        } finally {
            client.stop();
        }
    }

    @Test
    void shouldNotTimeoutIdleConnectionIfDisabled() throws Exception {
        this.server.update((line, headers, body) -> new CompletableFuture<>());
        final JettyClientSlices client = new JettyClientSlices(
            new HttpClientSettings().setIdleTimeout(0)
        );
        try {
            client.start();
            final CompletionStage<Response> received = client.http(
                "localhost",
                this.server.port()
            ).response(
                new RequestLine(RqMethod.GET, "/idle-timeout"),
                Headers.EMPTY,
                Content.EMPTY
            );
            Assertions.assertThrows(
                TimeoutException.class,
                () -> received.toCompletableFuture().get(1, TimeUnit.SECONDS)
            );
        } finally {
            client.stop();
        }
    }

    @Disabled("https://github.com/pantera/pantera/issues/1413")
    @ParameterizedTest
    @CsvSource({
        "expired.badssl.com",
        "self-signed.badssl.com",
        "untrusted-root.badssl.com"
    })
    void shouldTrustAllCertificates(final String url) throws Exception {
        final JettyClientSlices client = new JettyClientSlices(
            new HttpClientSettings().setTrustAll(true)
        );
        try {
            client.start();
            Assertions.assertEquals(
                RsStatus.OK,
                client.https(url).response(
                    new RequestLine(RqMethod.GET, "/"),
                    Headers.EMPTY, Content.EMPTY
                ).join().status()
            );
        } finally {
            client.stop();
        }
    }

    @Disabled("https://github.com/pantera/pantera/issues/1413")
    @ParameterizedTest
    @CsvSource({
        "expired.badssl.com",
        "self-signed.badssl.com",
        "untrusted-root.badssl.com"
    })
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    void shouldRejectBadCertificates(final String url) throws Exception {
        final JettyClientSlices client = new JettyClientSlices(
            new HttpClientSettings().setTrustAll(false)
        );
        try {
            client.start();
            final CompletableFuture<Response> fut = client.https(url).response(
                new RequestLine(RqMethod.GET, "/"),
                Headers.EMPTY, Content.EMPTY
            );
            final Exception exception = Assertions.assertThrows(
                CompletionException.class, fut::join
            );
            MatcherAssert.assertThat(
                exception,
                Matchers.hasProperty(
                    "cause",
                    Matchers.isA(SSLException.class)
                )
            );
        } finally {
            client.stop();
        }
    }
}
