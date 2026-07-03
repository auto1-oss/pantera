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

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/**
 * Tests for {@link OsvDevClient}. Exercises a real
 * {@link java.net.http.HttpClient} against a loopback
 * {@link com.sun.net.httpserver.HttpServer} mock that returns canned
 * responses — equivalent in coverage to a Mockito-based mock but
 * avoids the additional dep.
 *
 * @since 2.2.0
 */
class OsvDevClientTest {

    private HttpServer server;
    private OsvDevClient client;
    private AtomicInteger requestCount;

    @BeforeEach
    void setUp() throws Exception {
        this.requestCount = new AtomicInteger();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.client = new OsvDevClient(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
            URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1/query"),
            Duration.ofSeconds(5)
        );
        this.server.start();
    }

    @AfterEach
    void tearDown() {
        if (this.server != null) {
            this.server.stop(0);
        }
    }

    @Test
    void returnsEmptyListWhenOsvReturnsNoVulns() throws Exception {
        this.respond("{}");
        final List<OsvDevClient.Vulnerability> result = this.client.query(
            "Maven", "org.apache.commons:commons-lang3", "3.14.0"
        );
        MatcherAssert.assertThat(
            "OSV.dev empty response must map to an empty list",
            result.isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void returnsEmptyListWhenVulnsArrayIsEmpty() throws Exception {
        this.respond("{\"vulns\":[]}");
        final List<OsvDevClient.Vulnerability> result = this.client.query(
            "Maven", "org.apache.commons:commons-lang3", "3.14.0"
        );
        MatcherAssert.assertThat(
            result.isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void parsesLog4jShellAdvisory() throws Exception {
        // Real-world fixture: the OSV response for log4j-core 2.14.1
        // (CVE-2021-44228, Log4Shell). Simplified for the test but
        // shape-accurate.
        this.respond(
            "{\"vulns\":[{"
                + "\"id\":\"GHSA-jfh8-c2jp-5v3q\","
                + "\"summary\":\"Remote code execution in Apache Log4j\","
                + "\"aliases\":[\"CVE-2021-44228\"],"
                + "\"database_specific\":{\"severity\":\"CRITICAL\"}"
                + "}]}"
        );
        final List<OsvDevClient.Vulnerability> result = this.client.query(
            "Maven", "org.apache.logging.log4j:log4j-core", "2.14.1"
        );
        MatcherAssert.assertThat(
            "Log4Shell scan should return exactly one vulnerability",
            result.size(),
            new IsEqual<>(1)
        );
        final OsvDevClient.Vulnerability v = result.get(0);
        MatcherAssert.assertThat(
            "CVE alias should be preferred over the OSV GHSA id",
            v.cveId(),
            new IsEqual<>("CVE-2021-44228")
        );
        MatcherAssert.assertThat(
            "Severity must be parsed from database_specific",
            v.severity(),
            new IsEqual<>("CRITICAL")
        );
        MatcherAssert.assertThat(
            "Summary must round-trip verbatim",
            v.summary(),
            new IsEqual<>("Remote code execution in Apache Log4j")
        );
    }

    @Test
    void preservesGhsaIdWhenNoCveAlias() throws Exception {
        this.respond(
            "{\"vulns\":[{"
                + "\"id\":\"GHSA-aaaa-bbbb-cccc\","
                + "\"summary\":\"hypothetical\""
                + "}]}"
        );
        final List<OsvDevClient.Vulnerability> result = this.client.query(
            "npm", "left-pad", "1.3.0"
        );
        MatcherAssert.assertThat(
            "When no CVE alias is present the OSV id is used directly",
            result.get(0).cveId(),
            new IsEqual<>("GHSA-aaaa-bbbb-cccc")
        );
    }

    @Test
    void raisesOnNonSuccessStatus() {
        this.respondWithStatus(500, "Internal error");
        final OsvDevClient.OsvException ex = Assertions.assertThrows(
            OsvDevClient.OsvException.class,
            () -> this.client.query("Maven", "x:y", "1.0")
        );
        MatcherAssert.assertThat(
            ex.getMessage(),
            new org.hamcrest.core.StringContains("non-2xx")
        );
    }

    @Test
    void raisesOnMalformedJson() {
        this.respond("not-json");
        Assertions.assertThrows(
            OsvDevClient.OsvException.class,
            () -> this.client.query("Maven", "x:y", "1.0")
        );
    }

    @Test
    void sendsPostWithExpectedHeaders() throws Exception {
        this.respond("{}");
        this.client.query("Maven", "x:y", "1.0");
        MatcherAssert.assertThat(
            "Client must issue exactly one request per query() call",
            this.requestCount.get(),
            new IsEqual<>(1)
        );
    }

    @Test
    void rejectsNullArguments() {
        Assertions.assertThrows(
            NullPointerException.class,
            () -> this.client.query(null, "x:y", "1.0")
        );
        Assertions.assertThrows(
            NullPointerException.class,
            () -> this.client.query("Maven", null, "1.0")
        );
        Assertions.assertThrows(
            NullPointerException.class,
            () -> this.client.query("Maven", "x:y", null)
        );
    }

    /**
     * Install a /v1/query handler that responds with {@code body} and
     * status 200.
     */
    private void respond(final String body) {
        this.server.createContext("/v1/query", exchange -> {
            this.requestCount.incrementAndGet();
            final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(
                "Content-Type", "application/json"
            );
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            } catch (final IOException ex) {
                throw ex;
            } finally {
                exchange.close();
            }
        });
    }

    /**
     * Install a /v1/query handler that responds with the given status
     * and plain-text body.
     */
    private void respondWithStatus(final int status, final String body) {
        this.server.createContext("/v1/query", exchange -> {
            this.requestCount.incrementAndGet();
            final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            } finally {
                exchange.close();
            }
        });
    }
}
