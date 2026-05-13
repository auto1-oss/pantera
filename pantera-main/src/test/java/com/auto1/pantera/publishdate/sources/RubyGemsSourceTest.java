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
package com.auto1.pantera.publishdate.sources;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Instant;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class RubyGemsSourceTest {

    @RegisterExtension
    static final WireMockExtension WM = WireMockExtension.newInstance()
        .options(options().dynamicPort()).build();

    static Vertx vertx;
    static WebClient client;

    @BeforeAll
    static void setUp() {
        vertx = Vertx.vertx();
        client = WebClient.create(vertx);
    }

    @AfterAll
    static void tearDown() {
        client.close();
        vertx.close();
    }

    @Test
    void picksMatchingVersion() throws Exception {
        WM.stubFor(get(urlEqualTo("/api/v1/versions/rails.json")).willReturn(okJson(
            "[{\"number\":\"7.1.0\",\"created_at\":\"2023-10-05T19:00:00.000Z\"},"
            + "{\"number\":\"7.0.0\",\"created_at\":\"2021-12-15T22:00:00.000Z\"}]")));
        final var src = new RubyGemsSource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.of(Instant.parse("2023-10-05T19:00:00Z")),
            src.fetch("rails", "7.1.0").get());
    }

    @Test
    void empty404() throws Exception {
        WM.stubFor(get(urlEqualTo("/api/v1/versions/nope.json")).willReturn(notFound()));
        final var src = new RubyGemsSource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.empty(), src.fetch("nope", "1.0").get());
    }

    @Test
    void emptyWhenVersionMissing() throws Exception {
        WM.stubFor(get(urlEqualTo("/api/v1/versions/x.json")).willReturn(okJson(
            "[{\"number\":\"1.0\",\"created_at\":\"2020-01-01T00:00:00.000Z\"}]")));
        final var src = new RubyGemsSource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.empty(), src.fetch("x", "9.9").get());
    }
}
