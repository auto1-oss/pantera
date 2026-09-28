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
import com.auto1.pantera.composer.JsonPackage;
import com.auto1.pantera.composer.Repository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonString;
import javax.json.JsonValue;
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
            final Optional<String> query = Optional.ofNullable(matcher.group("version"));
            return body.asBytesFuture().thenCompose(bytes -> {
                final JsonObject json;
                try (JsonReader reader = Json.createReader(new ByteArrayInputStream(bytes))) {
                    json = reader.readObject();
                } catch (final JsonException | IllegalStateException ex) {
                    return ResponseBuilder.badRequest()
                        .textBody("The body must be a Composer package JSON object")
                        .completedFuture();
                }
                return this.guard(json, query).thenCompose(verdict -> {
                    if (verdict == ReleaseGuard.Verdict.CONFLICT) {
                        return ResponseBuilder.from(RsStatus.CONFLICT)
                            .textBody(
                                "This version is already published with different metadata;"
                                    + " publish a new version instead"
                            )
                            .completedFuture();
                    }
                    if (verdict == ReleaseGuard.Verdict.IDENTICAL) {
                        return ResponseBuilder.created().completedFuture();
                    }
                    return this.repository.addJson(new Content.From(bytes), query)
                        .thenApply(nothing -> ResponseBuilder.created().build());
                });
            });
        }
        return ResponseBuilder.badRequest().completedFuture();
    }

    /**
     * Immutability verdict for a registration; {@code NEW} when the package
     * name or version cannot be read (the repository reports those).
     *
     * @param json Package JSON
     * @param query Version from the query string
     * @return Verdict
     */
    private CompletableFuture<ReleaseGuard.Verdict> guard(
        final JsonObject json, final Optional<String> query
    ) {
        final JsonValue name = json.get("name");
        final JsonValue vers = json.get(JsonPackage.VRSN);
        final Optional<String> version = vers instanceof JsonString str
            ? Optional.of(str.getString()) : query;
        if (!(name instanceof JsonString pkg) || version.isEmpty()
            || pkg.getString().split("/").length != 2) {
            return CompletableFuture.completedFuture(ReleaseGuard.Verdict.NEW);
        }
        return new ReleaseGuard(this.repository).checkEntry(pkg.getString(), version.get(), json);
    }
}
