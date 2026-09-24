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
package com.auto1.pantera.docker.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.docker.perms.DockerRegistryPermission;
import com.auto1.pantera.docker.perms.RegistryCategory;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.rq.RequestLine;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonException;
import javax.json.JsonReader;
import javax.json.JsonString;

/**
 * Catalog entity in Docker HTTP API.
 * See <a href="https://docs.docker.com/registry/spec/api/#catalog">Catalog</a>.
 */
public final class CatalogSlice extends DockerActionSlice {

    public CatalogSlice(Docker docker) {
        super(docker);
    }

    @Override
    public DockerRegistryPermission permission(RequestLine line) {
        return new DockerRegistryPermission(docker.registryName(), RegistryCategory.CATALOG.mask());
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(ignored -> {
            final Pagination page = Pagination.from(line.uri());
            return this.docker.catalog(page)
                .thenCompose(catalog -> catalog.json().asBytesFuture())
                .thenApply(bytes -> CatalogSlice.render(line, page, bytes));
        });
    }

    /**
     * Render the catalog page; a full page links to the next one.
     *
     * @param line Request line
     * @param page Requested page
     * @param bytes Catalog JSON
     * @return Response
     */
    private static Response render(
        final RequestLine line, final Pagination page, final byte[] bytes
    ) {
        final ResponseBuilder found = ResponseBuilder.ok()
            .header(ContentType.json())
            .body(bytes);
        CatalogSlice.parse(bytes)
            .flatMap(names -> page.nextLink(line.uri().getPath(), names))
            .ifPresent(link -> found.header("Link", link));
        return found.build();
    }

    /**
     * Repository names of a catalog JSON document.
     *
     * @param bytes Catalog JSON
     * @return Names, empty when the document does not parse (it is then
     *  relayed as is)
     */
    private static Optional<List<String>> parse(final byte[] bytes) {
        Optional<List<String>> names;
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(bytes))) {
            final JsonArray repos = reader.readObject().getJsonArray("repositories");
            names = Optional.of(
                repos == null ? List.of()
                    : repos.getValuesAs(JsonString.class).stream()
                        .map(JsonString::getString).toList()
            );
        } catch (final JsonException | ClassCastException ex) {
            names = Optional.empty();
        }
        return names;
    }
}
