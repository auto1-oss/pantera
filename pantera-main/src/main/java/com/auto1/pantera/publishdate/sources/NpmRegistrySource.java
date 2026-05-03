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
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Canonical npm publish dates from {@code registry.npmjs.org/{pkg}}'s
 * {@code time["{version}"]} field.
 */
public final class NpmRegistrySource implements PublishDateSource {

    private static final String DEFAULT_BASE_URL = "https://registry.npmjs.org";
    private static final long TIMEOUT_MS = 5_000L;

    private final WebClient client;
    private final String baseUrl;

    public NpmRegistrySource(final WebClient client) {
        this(client, DEFAULT_BASE_URL);
    }

    public NpmRegistrySource(final WebClient client, final String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    @Override
    public String repoType() {
        return "npm";
    }

    @Override
    public String sourceId() {
        return "npm_registry";
    }

    @Override
    public CompletableFuture<Optional<Instant>> fetch(final String name, final String version) {
        final URI base = URI.create(this.baseUrl);
        final boolean ssl = "https".equals(base.getScheme());
        final int port = base.getPort() == -1 ? (ssl ? 443 : 80) : base.getPort();
        final String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);

        final CompletableFuture<Optional<Instant>> out = new CompletableFuture<>();
        this.client.get(port, base.getHost(), "/" + encoded)
            .ssl(ssl)
            .putHeader("User-Agent", com.auto1.pantera.http.EcosystemUserAgents.NPM)
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
                        new RuntimeException("npm registry 5xx: " + resp.statusCode())
                    );
                    return;
                }
                if (resp.statusCode() >= 400) {
                    out.complete(Optional.empty());
                    return;
                }
                try {
                    final JsonObject body = resp.bodyAsJsonObject();
                    final JsonObject time = body.getJsonObject("time");
                    if (time == null) {
                        out.complete(Optional.empty());
                        return;
                    }
                    final String iso = time.getString(version);
                    out.complete(iso == null ? Optional.empty() : Optional.of(Instant.parse(iso)));
                } catch (Exception ex) {
                    out.completeExceptionally(ex);
                }
            });
        return out;
    }
}
