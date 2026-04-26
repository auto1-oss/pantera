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
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class NpmRegistrySourceTest {

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
    void parsesIsoTimeForVersion() throws Exception {
        WM.stubFor(get(urlEqualTo("/lodash")).willReturn(okJson(
            "{\"name\":\"lodash\",\"time\":{"
            + "\"created\":\"2012-04-23T16:37:11.912Z\","
            + "\"4.17.21\":\"2021-02-20T15:42:16.891Z\","
            + "\"4.17.20\":\"2020-08-13T14:00:00.000Z\""
            + "}}")));
        final var src = new NpmRegistrySource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.of(Instant.parse("2021-02-20T15:42:16.891Z")),
            src.fetch("lodash", "4.17.21").get());
    }

    @Test
    void scopedPackageEncodesSlash() throws Exception {
        WM.stubFor(get(urlEqualTo("/%40types%2Fnode")).willReturn(okJson(
            "{\"time\":{\"20.0.0\":\"2023-05-01T00:00:00.000Z\"}}")));
        final var src = new NpmRegistrySource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.of(Instant.parse("2023-05-01T00:00:00.000Z")),
            src.fetch("@types/node", "20.0.0").get());
    }

    @Test
    void emptyWhenVersionMissing() throws Exception {
        WM.stubFor(get(urlEqualTo("/lodash")).willReturn(okJson(
            "{\"time\":{\"created\":\"2012-04-23T16:37:11.912Z\"}}")));
        final var src = new NpmRegistrySource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.empty(), src.fetch("lodash", "99.99.99").get());
    }

    @Test
    void empty404() throws Exception {
        WM.stubFor(get(urlEqualTo("/nope")).willReturn(notFound()));
        final var src = new NpmRegistrySource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.empty(), src.fetch("nope", "1.0").get());
    }

    @Test
    void exceptionOn5xx() {
        WM.stubFor(get(urlEqualTo("/x")).willReturn(serverError()));
        final var src = new NpmRegistrySource(client, "http://localhost:" + WM.getPort());
        assertThrows(Exception.class, () -> src.fetch("x", "1").get());
    }
}
