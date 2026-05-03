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
 * Canonical RubyGems publish dates from {@code rubygems.org/api/v1/versions/{gem}.json}.
 * Response is an array of {@code {number, created_at}} objects.
 */
public final class RubyGemsSource implements PublishDateSource {

    private static final String DEFAULT_BASE_URL = "https://rubygems.org";
    private static final long TIMEOUT_MS = 5_000L;

    private final WebClient client;
    private final String baseUrl;

    public RubyGemsSource(final WebClient client) {
        this(client, DEFAULT_BASE_URL);
    }

    public RubyGemsSource(final WebClient client, final String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    @Override
    public String repoType() {
        return "gem";
    }

    @Override
    public String sourceId() {
        return "rubygems";
    }

    @Override
    public CompletableFuture<Optional<Instant>> fetch(final String name, final String version) {
        final URI base = URI.create(this.baseUrl);
        final boolean ssl = "https".equals(base.getScheme());
        final int port = base.getPort() == -1 ? (ssl ? 443 : 80) : base.getPort();

        final CompletableFuture<Optional<Instant>> out = new CompletableFuture<>();
        this.client.get(port, base.getHost(), "/api/v1/versions/" + name + ".json")
            .ssl(ssl)
            .putHeader("User-Agent", com.auto1.pantera.http.EcosystemUserAgents.BUNDLER)
            .timeout(TIMEOUT_MS)
            .send(ar -> {
                if (ar.failed()) {
                    out.completeExceptionally(ar.cause());
                    return;
                }
                final var resp = ar.result();
                if (resp.statusCode() == 404) {
                    out.complete(Optional.empty());
                    return;
                }
                if (resp.statusCode() >= 500) {
                    out.completeExceptionally(
                        new RuntimeException("RubyGems 5xx: " + resp.statusCode())
                    );
                    return;
                }
                if (resp.statusCode() >= 400) {
                    out.complete(Optional.empty());
                    return;
                }
                try {
                    final JsonArray arr = resp.bodyAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        final JsonObject ver = arr.getJsonObject(i);
                        if (version.equals(ver.getString("number"))) {
                            final String createdAt = ver.getString("created_at");
                            if (createdAt == null) {
                                out.complete(Optional.empty());
                            } else {
                                out.complete(Optional.of(Instant.parse(createdAt)));
                            }
                            return;
                        }
                    }
                    out.complete(Optional.empty());
                } catch (Exception ex) {
                    out.completeExceptionally(ex);
                }
            });
        return out;
    }
}
