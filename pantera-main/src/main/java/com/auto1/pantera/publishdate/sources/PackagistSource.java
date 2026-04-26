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
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Canonical Composer publish dates from Packagist v2 metadata at
 * {@code repo.packagist.org/p2/{vendor}/{pkg}.json}. The package name is
 * {@code vendor/package}; the response has {@code packages.{vendor/pkg}[]}
 * with {@code version} and {@code time} (ISO-8601 with offset).
 */
public final class PackagistSource implements PublishDateSource {

    private static final String DEFAULT_BASE_URL = "https://repo.packagist.org";
    private static final long TIMEOUT_MS = 5_000L;

    private final WebClient client;
    private final String baseUrl;

    public PackagistSource(final WebClient client) {
        this(client, DEFAULT_BASE_URL);
    }

    public PackagistSource(final WebClient client, final String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    @Override
    public String repoType() {
        return "composer";
    }

    @Override
    public String sourceId() {
        return "packagist";
    }

    @Override
    public CompletableFuture<Optional<Instant>> fetch(final String name, final String version) {
        final int slash = name.indexOf('/');
        if (slash <= 0 || slash == name.length() - 1) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        final URI base = URI.create(this.baseUrl);
        final boolean ssl = "https".equals(base.getScheme());
        final int port = base.getPort() == -1 ? (ssl ? 443 : 80) : base.getPort();

        final CompletableFuture<Optional<Instant>> out = new CompletableFuture<>();
        this.client.get(port, base.getHost(), "/p2/" + name + ".json")
            .ssl(ssl)
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
                        new RuntimeException("Packagist 5xx: " + resp.statusCode())
                    );
                    return;
                }
                if (resp.statusCode() >= 400) {
                    out.complete(Optional.empty());
                    return;
                }
                try {
                    final JsonObject body = resp.bodyAsJsonObject();
                    final JsonObject packages = body.getJsonObject("packages");
                    if (packages == null) {
                        out.complete(Optional.empty());
                        return;
                    }
                    final JsonArray versions = packages.getJsonArray(name);
                    if (versions == null) {
                        out.complete(Optional.empty());
                        return;
                    }
                    for (int i = 0; i < versions.size(); i++) {
                        final JsonObject ver = versions.getJsonObject(i);
                        if (version.equals(ver.getString("version"))) {
                            final String time = ver.getString("time");
                            if (time == null) {
                                out.complete(Optional.empty());
                            } else {
                                out.complete(Optional.of(OffsetDateTime.parse(time).toInstant()));
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
