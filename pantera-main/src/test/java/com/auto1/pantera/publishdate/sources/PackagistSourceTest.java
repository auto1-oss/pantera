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

final class PackagistSourceTest {

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
    void picksVersionTime() throws Exception {
        WM.stubFor(get(urlEqualTo("/p2/symfony/console.json")).willReturn(okJson(
            "{\"packages\":{\"symfony/console\":["
            + "{\"version\":\"v6.4.0\",\"time\":\"2023-11-23T16:00:00+00:00\"},"
            + "{\"version\":\"v6.3.0\",\"time\":\"2023-05-29T15:00:00+00:00\"}"
            + "]}}")));
        final var src = new PackagistSource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.of(Instant.parse("2023-11-23T16:00:00Z")),
            src.fetch("symfony/console", "v6.4.0").get());
    }

    @Test
    void empty404() throws Exception {
        WM.stubFor(get(urlEqualTo("/p2/x/y.json")).willReturn(notFound()));
        final var src = new PackagistSource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.empty(), src.fetch("x/y", "1.0").get());
    }

    @Test
    void emptyWhenVersionMissing() throws Exception {
        WM.stubFor(get(urlEqualTo("/p2/x/y.json")).willReturn(okJson(
            "{\"packages\":{\"x/y\":[{\"version\":\"1.0\","
            + "\"time\":\"2020-01-01T00:00:00+00:00\"}]}}")));
        final var src = new PackagistSource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.empty(), src.fetch("x/y", "9.9").get());
    }

    @Test
    void emptyForUnsplittablePackage() throws Exception {
        final var src = new PackagistSource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.empty(), src.fetch("noslash", "1.0").get());
    }
}
