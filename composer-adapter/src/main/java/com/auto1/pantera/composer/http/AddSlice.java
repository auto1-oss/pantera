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
package com.auto1.pantera.composer.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.composer.Repository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slice for adding a package to the repository in JSON format.
 */
final class AddSlice implements Slice {

    /**
     * RegEx pattern for matching path.
     */
    public static final Pattern PATH_PATTERN = Pattern.compile("^/(\\?version=(?<version>.*))?$");

    /**
     * Repository.
     */
    private final Repository repository;

    /**
     * @param repository Repository.
     */
    AddSlice(final Repository repository) {
        this.repository = repository;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String path = line.uri().toString();
        final Matcher matcher = AddSlice.PATH_PATTERN.matcher(path);
        if (matcher.matches()) {
            return this.repository.addJson(
                new Content.From(body), Optional.ofNullable(matcher.group("version"))
            ).thenApply(nothing -> ResponseBuilder.created().build());
        }
        return ResponseBuilder.badRequest().completedFuture();
    }
}
