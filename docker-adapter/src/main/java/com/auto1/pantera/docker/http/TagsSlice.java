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
import com.auto1.pantera.docker.error.NameUnknownError;
import com.auto1.pantera.docker.misc.ImageRepositoryName;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.docker.misc.RqByRegex;
import com.auto1.pantera.docker.perms.DockerActions;
import com.auto1.pantera.docker.perms.DockerRepositoryPermission;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.rq.RequestLine;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonException;
import javax.json.JsonReader;
import javax.json.JsonString;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Tags entity in Docker HTTP API.
 * See <a href="https://docs.docker.com/registry/spec/api/#tags">Tags</a>.
 */
final class TagsSlice extends DockerActionSlice {

    public TagsSlice(Docker docker) {
        super(docker);
    }

    @Override
    public DockerRepositoryPermission permission(RequestLine line) {
        return new DockerRepositoryPermission(
            docker.registryName(), name(line), DockerActions.PULL.mask()
        );
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final Pagination page = Pagination.from(line.uri());
        final String name = name(line);
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(ignored ->
            this.docker.repo(name)
                .manifests()
                .tags(page)
                .thenCompose(
                    tags -> tags.json().asBytesFuture().thenApply(
                        bytes -> TagsSlice.render(line, name, page, tags.complete(), bytes)
                    )
                )
        );
    }

    /**
     * Render the tags listing. An empty first page means the repository
     * holds no such name: 404 NAME_UNKNOWN (marked non-authoritative for
     * the negative cache when a source could not be read). A full page
     * links to the next one.
     *
     * @param line Request line
     * @param name Image name
     * @param page Requested page
     * @param complete Whether every tag source answered
     * @param bytes Tags JSON
     * @return Response
     */
    private static Response render(
        final RequestLine line, final String name, final Pagination page,
        final boolean complete, final byte[] bytes
    ) {
        final Optional<List<String>> listed = TagsSlice.parse(bytes);
        final Response response;
        if (listed.isPresent() && listed.get().isEmpty()
            && page.last() == null && page.limit() > 0) {
            final ResponseBuilder missing = ResponseBuilder.notFound();
            if (!complete) {
                missing.header(NegativeCache.SKIP_HEADER, "true");
            }
            response = missing.jsonBody(new NameUnknownError(name).json()).build();
        } else {
            final ResponseBuilder found = ResponseBuilder.ok()
                .header(ContentType.json())
                .body(bytes);
            if (listed.isPresent() && page.limit() > 0 && page.limit() != Integer.MAX_VALUE
                && listed.get().size() >= page.limit()) {
                found.header(
                    "Link",
                    String.format(
                        "<%s>; rel=\"next\"",
                        new Pagination(listed.get().get(listed.get().size() - 1), page.limit())
                            .uriWithPagination(line.uri().getPath())
                    )
                );
            }
            response = found.build();
        }
        return response;
    }

    /**
     * Tag names of a tags JSON document.
     *
     * @param bytes Tags JSON
     * @return Names, empty when the document does not parse (it is then
     *  relayed as is)
     */
    private static Optional<List<String>> parse(final byte[] bytes) {
        Optional<List<String>> names;
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(bytes))) {
            final JsonArray tags = reader.readObject().getJsonArray("tags");
            names = Optional.of(
                tags == null ? List.of()
                    : tags.getValuesAs(JsonString.class).stream()
                        .map(JsonString::getString).toList()
            );
        } catch (final JsonException | ClassCastException ex) {
            names = Optional.empty();
        }
        return names;
    }

    private String name(RequestLine line) {
        return ImageRepositoryName.validate(new RqByRegex(line, PathPatterns.TAGS)
            .path().group("name"));
    }
}
