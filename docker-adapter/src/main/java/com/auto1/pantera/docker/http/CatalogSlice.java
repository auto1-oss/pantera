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
import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.docker.perms.DockerRegistryPermission;
import com.auto1.pantera.docker.perms.RegistryCategory;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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
        final Pagination pagination = Pagination.from(line.uri());
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(ignored ->
            this.docker.catalog(pagination)
                .thenApply(
                    catalog -> {
                        final ResponseBuilder builder = ResponseBuilder.ok()
                            .header(ContentType.json());
                        nextLink(pagination, catalog).ifPresent(
                            link -> builder.header(new Header("Link", link))
                        );
                        return builder.body(catalog.json()).build();
                    }
                )
        );
    }

    /**
     * Builds the {@code Link: <...>; rel="next"} header value when the catalog page was
     * truncated (more repositories exist beyond {@code n}), per the Docker Distribution spec.
     */
    private static Optional<String> nextLink(final Pagination pagination, final Catalog catalog) {
        if (!catalog.hasNext()) {
            return Optional.empty();
        }
        return catalog.nextCursor().map(
            cursor -> String.format(
                "<%s>; rel=\"next\"",
                new Pagination(cursor, pagination.limit()).uriWithPagination("/v2/_catalog")
            )
        );
    }
}
