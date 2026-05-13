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
import io.vertx.ext.web.client.WebClientOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Instant;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MavenCentralSourceTest {

    @RegisterExtension
    static final WireMockExtension WM = WireMockExtension.newInstance()
        .options(options().dynamicPort()).build();

    static Vertx vertx;
    static WebClient client;

    @BeforeAll
    static void setUp() {
        vertx = Vertx.vertx();
        client = WebClient.create(vertx, new WebClientOptions().setUserAgent("pantera-test"));
    }

    @AfterAll
    static void tearDown() {
        client.close();
        vertx.close();
    }

    @Test
    void returnsTimestampForKnownArtifact() throws Exception {
        WM.stubFor(get(urlPathEqualTo("/solrsearch/select"))
            .withQueryParam("q", equalTo("g:org.apache.commons AND a:commons-lang3 AND v:3.12.0"))
            .willReturn(okJson("{\"response\":{\"numFound\":1,\"docs\":["
                + "{\"g\":\"org.apache.commons\",\"a\":\"commons-lang3\","
                + "\"v\":\"3.12.0\",\"timestamp\":1620000000000}"
                + "]}}")));
        final var src = new MavenCentralSource(client, "http://localhost:" + WM.getPort());

        final Optional<Instant> result = src.fetch("org.apache.commons.commons-lang3", "3.12.0").get();
        assertEquals(Optional.of(Instant.ofEpochMilli(1620000000000L)), result);
    }

    @Test
    void returnsEmptyWhenNumFoundZero() throws Exception {
        WM.stubFor(get(urlPathEqualTo("/solrsearch/select"))
            .willReturn(okJson("{\"response\":{\"numFound\":0,\"docs\":[]}}")));
        final var src = new MavenCentralSource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.empty(), src.fetch("nope.nope", "1.0").get());
    }

    @Test
    void completesExceptionallyOn5xx() {
        WM.stubFor(get(urlPathEqualTo("/solrsearch/select"))
            .willReturn(serverError()));
        final var src = new MavenCentralSource(client, "http://localhost:" + WM.getPort());
        final Exception ex = assertThrows(Exception.class, () -> src.fetch("x.y", "1").get());
        assertTrue(ex.getMessage().contains("Solr"),
            "Should signal Solr-side error, got: " + ex.getMessage());
    }

    @Test
    void splitsOnLastDot() throws Exception {
        WM.stubFor(get(urlPathEqualTo("/solrsearch/select"))
            .withQueryParam("q", equalTo("g:org.codehaus.plexus AND a:plexus-utils AND v:4.0.0"))
            .willReturn(okJson("{\"response\":{\"numFound\":1,\"docs\":["
                + "{\"timestamp\":1700000000000}]}}")));
        final var src = new MavenCentralSource(client, "http://localhost:" + WM.getPort());
        assertEquals(
            Optional.of(Instant.ofEpochMilli(1700000000000L)),
            src.fetch("org.codehaus.plexus.plexus-utils", "4.0.0").get()
        );
    }

    @Test
    void emptyForUnsplittableName() throws Exception {
        final var src = new MavenCentralSource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.empty(), src.fetch("nodot", "1.0").get());
    }
}
