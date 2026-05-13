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

import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("live")
final class MavenHeadSourceLiveTest {

    static Vertx vertx;
    static WebClient client;

    @BeforeAll
    static void setUp() {
        vertx = Vertx.vertx();
        client = WebClient.create(vertx, new WebClientOptions()
            .setUserAgent(com.auto1.pantera.http.PanteraUserAgent
                .userAgentWithComponent("publish-date"))
            .setConnectTimeout(5_000));
    }

    @AfterAll
    static void tearDown() {
        client.close();
        vertx.close();
    }

    @Test
    void mavenCentralCommonsLang3() throws Exception {
        final var src = new MavenHeadSource(client);
        final Optional<Instant> result =
            src.fetch("org.apache.commons.commons-lang3", "3.18.0").get();
        assertTrue(result.isPresent(), "commons-lang3:3.18.0 must exist on Maven Central");
        final LocalDate date = result.get().atZone(ZoneOffset.UTC).toLocalDate();
        assertEquals(LocalDate.of(2025, 7, 6), date);
    }

    @Test
    void mavenCentralGuava() throws Exception {
        final var src = new MavenHeadSource(client);
        final Optional<Instant> result =
            src.fetch("com.google.guava.guava", "33.5.0-jre").get();
        assertTrue(result.isPresent(), "guava:33.5.0-jre must exist on Maven Central");
    }

    @Test
    void mavenCentralMavenCompilerPlugin() throws Exception {
        final var src = new MavenHeadSource(client);
        final Optional<Instant> result =
            src.fetch("org.apache.maven.plugins.maven-compiler-plugin", "3.13.0").get();
        assertTrue(result.isPresent());
        final LocalDate date = result.get().atZone(ZoneOffset.UTC).toLocalDate();
        assertEquals(LocalDate.of(2024, 3, 15), date);
    }

    @Test
    void jfrogFallbackForMavenCompilerPlugin() throws Exception {
        final var src = new JFrogStorageApiSource(
            client, "https://groovy.jfrog.io/artifactory", "plugins-release");
        final Optional<Instant> result =
            src.fetch("org.apache.maven.plugins.maven-compiler-plugin", "3.13.0").get();
        assertTrue(result.isPresent());
        final LocalDate date = result.get().atZone(ZoneOffset.UTC).toLocalDate();
        assertEquals(LocalDate.of(2024, 3, 15), date);
    }

    @Test
    void chainedReturnsFromMavenCentralFirst() throws Exception {
        final var head = new MavenHeadSource(client);
        final var jfrog = new JFrogStorageApiSource(
            client, "https://groovy.jfrog.io/artifactory", "plugins-release");
        final var chained = new ChainedPublishDateSource(head, jfrog);
        final Optional<Instant> result =
            chained.fetch("org.apache.commons.commons-lang3", "3.18.0").get();
        assertTrue(result.isPresent());
    }

    @Test
    void nonExistentArtifactReturnsEmpty() throws Exception {
        final var src = new MavenHeadSource(client);
        assertEquals(Optional.empty(),
            src.fetch("com.example.does-not-exist.xyzzy", "99.99.99").get());
    }

    @Test
    void swaggerAnnotationsJakartaRecent() throws Exception {
        final var src = new MavenHeadSource(client);
        final Optional<Instant> result =
            src.fetch("io.swagger.core.v3.swagger-annotations-jakarta", "2.2.28").get();
        assertTrue(result.isPresent(),
            "swagger-annotations-jakarta:2.2.28 must exist on Maven Central");
    }
}
