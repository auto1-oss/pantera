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

import com.auto1.pantera.publishdate.PublishDateSource;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Canonical Maven publish dates from Maven Central's Solr search API.
 * Pantera's name field for Maven is dot-joined {@code groupId.artifactId};
 * we split on the LAST dot.
 */
public final class MavenCentralSource implements PublishDateSource {

    private static final String DEFAULT_BASE_URL = "https://search.maven.org";
    private static final long TIMEOUT_MS = 5_000L;

    private final WebClient client;
    private final String baseUrl;

    public MavenCentralSource(final WebClient client) {
        this(client, DEFAULT_BASE_URL);
    }

    public MavenCentralSource(final WebClient client, final String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    @Override
    public String repoType() {
        return "maven";
    }

    @Override
    public String sourceId() {
        return "maven_central_solr";
    }

    @Override
    public CompletableFuture<Optional<Instant>> fetch(final String name, final String version) {
        final int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        final String groupId = name.substring(0, dot);
        final String artifactId = name.substring(dot + 1);
        final String query = "g:" + groupId + " AND a:" + artifactId + " AND v:" + version;

        final URI base = URI.create(this.baseUrl);
        final boolean ssl = "https".equals(base.getScheme());
        final int port = base.getPort() == -1 ? (ssl ? 443 : 80) : base.getPort();

        final CompletableFuture<Optional<Instant>> out = new CompletableFuture<>();
        this.client.get(port, base.getHost(), "/solrsearch/select")
            .addQueryParam("q", query)
            .addQueryParam("wt", "json")
            .addQueryParam("rows", "1")
            .ssl(ssl)
            .putHeader("User-Agent", com.auto1.pantera.http.EcosystemUserAgents.MAVEN)
            .timeout(TIMEOUT_MS)
            .send(ar -> {
                if (ar.failed()) {
                    out.completeExceptionally(ar.cause());
                    return;
                }
                final var resp = ar.result();
                if (resp.statusCode() >= 500) {
                    out.completeExceptionally(
                        new RuntimeException("Solr 5xx: " + resp.statusCode())
                    );
                    return;
                }
                if (resp.statusCode() >= 400) {
                    out.complete(Optional.empty());
                    return;
                }
                try {
                    final JsonObject body = resp.bodyAsJsonObject();
                    final JsonObject response = body.getJsonObject("response");
                    final JsonArray docs = response == null ? null : response.getJsonArray("docs");
                    if (docs == null || docs.isEmpty()) {
                        out.complete(Optional.empty());
                        return;
                    }
                    final long ts = docs.getJsonObject(0).getLong("timestamp");
                    out.complete(Optional.of(Instant.ofEpochMilli(ts)));
                } catch (Exception ex) {
                    out.completeExceptionally(ex);
                }
            });
        return out;
    }
}
