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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MavenHeadSourceTest {

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
    void parsesLastModifiedFromHead() throws Exception {
        WM.stubFor(head(urlEqualTo(
            "/org/apache/maven/plugins/maven-compiler-plugin/3.13.0/maven-compiler-plugin-3.13.0.pom"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Last-Modified", "Fri, 15 Mar 2024 07:28:40 GMT")));
        final var src = new MavenHeadSource(client, "http://localhost:" + WM.getPort());
        final Optional<Instant> result = src.fetch(
            "org.apache.maven.plugins.maven-compiler-plugin", "3.13.0").get();
        assertEquals(Optional.of(Instant.parse("2024-03-15T07:28:40Z")), result);
    }

    @Test
    void splitsGroupIdOnLastDot() throws Exception {
        WM.stubFor(head(urlEqualTo(
            "/com/google/guava/guava/33.5.0-jre/guava-33.5.0-jre.pom"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Last-Modified", "Mon, 01 Jan 2024 00:00:00 GMT")));
        final var src = new MavenHeadSource(client, "http://localhost:" + WM.getPort());
        final Optional<Instant> result = src.fetch("com.google.guava.guava", "33.5.0-jre").get();
        assertTrue(result.isPresent(), "Expected a result for guava path");
    }

    @Test
    void returnsEmptyOn404() throws Exception {
        WM.stubFor(head(urlEqualTo(
            "/com/example/artifact/1.0/artifact-1.0.pom"))
            .willReturn(aResponse().withStatus(404)));
        final var src = new MavenHeadSource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.empty(), src.fetch("com.example.artifact", "1.0").get());
    }

    @Test
    void completesExceptionallyOn5xx() {
        WM.stubFor(head(urlEqualTo(
            "/com/example/artifact/1.0/artifact-1.0.pom"))
            .willReturn(aResponse().withStatus(500)));
        final var src = new MavenHeadSource(client, "http://localhost:" + WM.getPort());
        assertThrows(Exception.class, () -> src.fetch("com.example.artifact", "1.0").get());
    }

    @Test
    void emptyForUnsplittableName() throws Exception {
        final var src = new MavenHeadSource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.empty(), src.fetch("nodot", "1.0").get());
    }

    @Test
    void returnsEmptyWhenNoLastModifiedHeader() throws Exception {
        WM.stubFor(head(urlEqualTo(
            "/com/example/artifact/2.0/artifact-2.0.pom"))
            .willReturn(aResponse().withStatus(200)));
        final var src = new MavenHeadSource(client, "http://localhost:" + WM.getPort());
        assertEquals(Optional.empty(), src.fetch("com.example.artifact", "2.0").get());
    }
}
