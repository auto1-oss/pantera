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
package com.auto1.pantera.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double of the Okta endpoints the password sign-in uses. It answers
 * primary authentication with one configured status, offers a push factor
 * and a TOTP factor, and records every call, so a test can prove which
 * requests Pantera sent.
 *
 * <p>It listens on the loopback address only: a wildcard bind may be given
 * a port another application already holds on 127.0.0.1.</p>
 *
 * @since 2.2.15
 */
final class FakeOkta implements AutoCloseable {

    /**
     * Path of primary authentication.
     */
    static final String AUTHN = "/api/v1/authn";

    /**
     * Path that verifies the push factor.
     */
    static final String PUSH = "/factors/push/verify";

    /**
     * Path that verifies the TOTP factor.
     */
    static final String TOTP = "/factors/totp/verify";

    /**
     * Path of the authorization endpoint.
     */
    static final String AUTHORIZE = "/oauth2/v1/authorize";

    /**
     * Calls received: "path body".
     */
    private final List<String> calls = new CopyOnWriteArrayList<>();

    /**
     * HTTP server.
     */
    private final HttpServer server;

    /**
     * HTTP status of primary authentication.
     */
    private final int code;

    /**
     * Okta status of primary authentication.
     */
    private final String status;

    /**
     * Ctor.
     *
     * @param code HTTP status of primary authentication
     * @param status Okta status of primary authentication, such as
     *  {@code MFA_REQUIRED}
     * @throws IOException When the server cannot start
     */
    FakeOkta(final int code, final String status) throws IOException {
        this.code = code;
        this.status = status;
        this.server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0
        );
        this.server.createContext(FakeOkta.AUTHN, this::authn);
        this.server.createContext(
            FakeOkta.PUSH,
            // Not "waiting": the client would poll for 30 seconds.
            exchange -> this.answer(exchange, 200, "{\"status\":\"LOCKED_OUT\"}")
        );
        this.server.createContext(
            FakeOkta.TOTP,
            exchange -> this.answer(
                exchange, 200, "{\"status\":\"SUCCESS\",\"sessionToken\":\"session\"}"
            )
        );
        this.server.createContext(
            FakeOkta.AUTHORIZE, exchange -> this.answer(exchange, 400, "{}")
        );
        this.server.start();
    }

    /**
     * Base URL.
     *
     * @return URL without a trailing slash
     */
    String url() {
        return String.format(
            "http://%s:%d",
            InetAddress.getLoopbackAddress().getHostAddress(),
            this.server.getAddress().getPort()
        );
    }

    /**
     * Number of calls to a path.
     *
     * @param path Path
     * @return Count
     */
    long count(final String path) {
        return this.calls.stream().filter(call -> call.startsWith(path + ' ')).count();
    }

    /**
     * Bodies of the calls to a path.
     *
     * @param path Path
     * @return Bodies, in order
     */
    List<String> bodies(final String path) {
        return this.calls.stream()
            .filter(call -> call.startsWith(path + ' '))
            .map(call -> call.substring(path.length() + 1))
            .toList();
    }

    @Override
    public void close() {
        this.server.stop(0);
    }

    private void authn(final HttpExchange exchange) throws IOException {
        final String body;
        if (this.code / 100 == 2) {
            body = String.join(
                "",
                "{\"status\":\"", this.status, "\",",
                "\"stateToken\":\"state\",\"sessionToken\":\"session\",",
                "\"_embedded\":{\"factors\":[",
                "{\"factorType\":\"push\",\"_links\":{\"verify\":{\"href\":\"",
                this.url(), FakeOkta.PUSH, "\"}}},",
                "{\"factorType\":\"token:software:totp\",\"_links\":{\"verify\":{\"href\":\"",
                this.url(), FakeOkta.TOTP, "\"}}}",
                "]}}"
            );
        } else {
            body = "{\"errorCode\":\"E0000004\",\"errorSummary\":\"Authentication failed\"}";
        }
        this.answer(exchange, this.code, body);
    }

    private void answer(final HttpExchange exchange, final int http, final String body)
        throws IOException {
        this.calls.add(
            exchange.getRequestURI().getPath() + ' '
                + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
        );
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(http, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
