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
import com.auto1.pantera.docker.misc.ImageRepositoryName;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.docker.misc.RqByRegex;
import com.auto1.pantera.docker.perms.DockerActions;
import com.auto1.pantera.docker.perms.DockerRepositoryPermission;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.rq.RequestLine;

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
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(ignored ->
            this.docker.repo(name(line))
                .manifests()
                .tags(Pagination.from(line.uri()))
                .thenApply(
                    tags -> ResponseBuilder.ok()
                        .header(ContentType.json())
                        .body(tags.json())
                        .build()
                )
        );
    }

    private String name(RequestLine line) {
        return ImageRepositoryName.validate(new RqByRegex(line, PathPatterns.TAGS)
            .path().group("name"));
    }
}
