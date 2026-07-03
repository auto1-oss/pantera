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
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class GoProxySourceTest {

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
    void readsTimeFromInfo() throws Exception {
        WM.stubFor(get(urlEqualTo("/github.com/spf13/cobra/@v/v1.8.0.info"))
            .willReturn(okJson("{\"Version\":\"v1.8.0\",\"Time\":\"2024-02-01T19:36:18Z\"}")));
        final var src = new GoProxySource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.of(Instant.parse("2024-02-01T19:36:18Z")),
            src.fetch("github.com/spf13/cobra", "v1.8.0").get());
    }

    @Test
    void escapesUppercaseInModulePath() throws Exception {
        WM.stubFor(get(urlEqualTo("/github.com/!burnt!sushi/toml/@v/v1.0.0.info"))
            .willReturn(okJson("{\"Version\":\"v1.0.0\",\"Time\":\"2022-01-01T00:00:00Z\"}")));
        final var src = new GoProxySource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.of(Instant.parse("2022-01-01T00:00:00Z")),
            src.fetch("github.com/BurntSushi/toml", "v1.0.0").get());
    }

    @Test
    void empty404() throws Exception {
        WM.stubFor(get(urlPathMatching("/.*")).willReturn(notFound()));
        final var src = new GoProxySource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.empty(), src.fetch("nope/nope", "v1").get());
    }

    @Test
    void exceptionOn5xx() {
        WM.stubFor(get(urlPathMatching("/.*")).willReturn(serverError()));
        final var src = new GoProxySource(client, "http://localhost:" + WM.getPort());
        assertThrows(Exception.class, () -> src.fetch("x/y", "v1").get());
    }
}
