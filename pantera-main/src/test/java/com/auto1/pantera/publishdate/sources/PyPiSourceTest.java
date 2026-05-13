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

final class PyPiSourceTest {

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
    void readsUploadTimeIso8601() throws Exception {
        WM.stubFor(get(urlEqualTo("/pypi/requests/2.31.0/json")).willReturn(okJson(
            "{\"info\":{\"name\":\"requests\"},\"urls\":["
            + "{\"upload_time_iso_8601\":\"2023-05-22T15:12:44.612000Z\","
            + "\"filename\":\"requests-2.31.0.tar.gz\"}"
            + "]}")));
        final var src = new PyPiSource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.of(Instant.parse("2023-05-22T15:12:44.612000Z")),
            src.fetch("requests", "2.31.0").get());
    }

    @Test
    void empty404() throws Exception {
        WM.stubFor(get(urlEqualTo("/pypi/nope/1.0/json")).willReturn(notFound()));
        final var src = new PyPiSource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.empty(), src.fetch("nope", "1.0").get());
    }

    @Test
    void emptyWhenUrlsArrayEmpty() throws Exception {
        WM.stubFor(get(urlEqualTo("/pypi/x/1.0/json")).willReturn(okJson(
            "{\"info\":{},\"urls\":[]}")));
        final var src = new PyPiSource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.empty(), src.fetch("x", "1.0").get());
    }

    @Test
    void exceptionOn5xx() {
        WM.stubFor(get(urlEqualTo("/pypi/x/1.0/json")).willReturn(serverError()));
        final var src = new PyPiSource(client, "http://localhost:" + WM.getPort());
        assertThrows(Exception.class, () -> src.fetch("x", "1.0").get());
    }
}
