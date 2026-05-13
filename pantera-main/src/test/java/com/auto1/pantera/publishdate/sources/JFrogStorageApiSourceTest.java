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

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class JFrogStorageApiSourceTest {

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
    void parsesLastModifiedFromStorageApi() throws Exception {
        WM.stubFor(get(urlEqualTo(
            "/api/storage/plugins-release/org/apache/maven/plugins/maven-compiler-plugin"
            + "/3.13.0/maven-compiler-plugin-3.13.0.pom"))
            .willReturn(okJson(
                "{\"lastModified\":\"2024-03-15T07:28:40.000Z\","
                + "\"created\":\"2024-07-30T06:56:05.138Z\"}")));
        final var src = new JFrogStorageApiSource(
            client, "http://localhost:" + WM.getPort(), "plugins-release");
        final Optional<Instant> result = src.fetch(
            "org.apache.maven.plugins.maven-compiler-plugin", "3.13.0").get();
        assertEquals(Optional.of(Instant.parse("2024-03-15T07:28:40.000Z")), result);
    }

    @Test
    void returnsEmptyOn404() throws Exception {
        WM.stubFor(get(urlEqualTo(
            "/api/storage/plugins-release/com/example/artifact/1.0/artifact-1.0.pom"))
            .willReturn(notFound()));
        final var src = new JFrogStorageApiSource(
            client, "http://localhost:" + WM.getPort(), "plugins-release");
        assertEquals(Optional.empty(), src.fetch("com.example.artifact", "1.0").get());
    }

    @Test
    void completesExceptionallyOn5xx() {
        WM.stubFor(get(urlEqualTo(
            "/api/storage/plugins-release/com/example/artifact/1.0/artifact-1.0.pom"))
            .willReturn(serverError()));
        final var src = new JFrogStorageApiSource(
            client, "http://localhost:" + WM.getPort(), "plugins-release");
        assertThrows(Exception.class, () -> src.fetch("com.example.artifact", "1.0").get());
    }

    @Test
    void emptyForUnsplittableName() throws Exception {
        final var src = new JFrogStorageApiSource(
            client, "http://localhost:" + WM.getPort(), "plugins-release");
        assertEquals(Optional.empty(), src.fetch("nodot", "1.0").get());
    }

    @Test
    void handlesMillisecondPrecision() throws Exception {
        WM.stubFor(get(urlEqualTo(
            "/api/storage/plugins-release/com/example/artifact/2.0/artifact-2.0.pom"))
            .willReturn(okJson(
                "{\"lastModified\":\"2024-06-30T07:19:16.000Z\"}")));
        final var src = new JFrogStorageApiSource(
            client, "http://localhost:" + WM.getPort(), "plugins-release");
        final Optional<Instant> result = src.fetch("com.example.artifact", "2.0").get();
        assertEquals(Optional.of(Instant.parse("2024-06-30T07:19:16Z")), result);
    }

    @Test
    void returnsEmptyWhenLastModifiedFieldMissing() throws Exception {
        WM.stubFor(get(urlEqualTo(
            "/api/storage/plugins-release/com/example/artifact/3.0/artifact-3.0.pom"))
            .willReturn(okJson(
                "{\"created\":\"2024-01-01T00:00:00.000Z\"}")));
        final var src = new JFrogStorageApiSource(
            client, "http://localhost:" + WM.getPort(), "plugins-release");
        assertEquals(Optional.empty(), src.fetch("com.example.artifact", "3.0").get());
    }
}
