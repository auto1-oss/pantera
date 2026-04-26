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
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Canonical Go module publish dates from {@code proxy.golang.org/{module}/@v/{ver}.info}'s
 * {@code Time} field. Module path uppercase chars are escaped per the Go module proxy
 * spec ({@code BurntSushi} → {@code !burnt!sushi}).
 */
public final class GoProxySource implements PublishDateSource {

    private static final String DEFAULT_BASE_URL = "https://proxy.golang.org";
    private static final long TIMEOUT_MS = 5_000L;

    private final WebClient client;
    private final String baseUrl;

    public GoProxySource(final WebClient client) {
        this(client, DEFAULT_BASE_URL);
    }

    public GoProxySource(final WebClient client, final String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    @Override
    public String repoType() {
        return "go";
    }

    @Override
    public String sourceId() {
        return "go_proxy";
    }

    @Override
    public CompletableFuture<Optional<Instant>> fetch(final String name, final String version) {
        final URI base = URI.create(this.baseUrl);
        final boolean ssl = "https".equals(base.getScheme());
        final int port = base.getPort() == -1 ? (ssl ? 443 : 80) : base.getPort();
        final String escaped = escapeModulePath(name);
        final String path = "/" + escaped + "/@v/" + version + ".info";

        final CompletableFuture<Optional<Instant>> out = new CompletableFuture<>();
        this.client.get(port, base.getHost(), path)
            .ssl(ssl)
            .timeout(TIMEOUT_MS)
            .send(ar -> {
                if (ar.failed()) {
                    out.completeExceptionally(ar.cause());
                    return;
                }
                final var resp = ar.result();
                if (resp.statusCode() == 404 || resp.statusCode() == 410) {
                    out.complete(Optional.empty());
                    return;
                }
                if (resp.statusCode() >= 500) {
                    out.completeExceptionally(
                        new RuntimeException("Go proxy 5xx: " + resp.statusCode())
                    );
                    return;
                }
                if (resp.statusCode() >= 400) {
                    out.complete(Optional.empty());
                    return;
                }
                try {
                    final JsonObject body = resp.bodyAsJsonObject();
                    final String iso = body.getString("Time");
                    out.complete(iso == null ? Optional.empty() : Optional.of(Instant.parse(iso)));
                } catch (Exception ex) {
                    out.completeExceptionally(ex);
                }
            });
        return out;
    }

    static String escapeModulePath(final String module) {
        final StringBuilder sb = new StringBuilder(module.length() + 8);
        for (int i = 0; i < module.length(); i++) {
            final char c = module.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                sb.append('!').append((char) (c + 32));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
