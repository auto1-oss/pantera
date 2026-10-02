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
import com.auto1.pantera.composer.ComposerBaseUrl;
import com.auto1.pantera.composer.MetadataLinks;
import com.auto1.pantera.composer.Name;
import com.auto1.pantera.composer.Packages;
import com.auto1.pantera.composer.Repository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.json.JsonException;

/**
 * Slice that serves package metadata, with its links re-rooted at the base URL
 * resolved for the request (see {@link ComposerBaseUrl}, {@link MetadataLinks}).
 */
public final class PackageMetadataSlice implements Slice {

    /**
     * RegEx pattern for package metadata path.
     * According to <a href="https://packagist.org/apidoc#get-package-data">docs</a>.
     * Also handles Satis cache-busting format: /p2/vendor/package$hash.json
     */
    public static final Pattern PACKAGE = Pattern.compile(
        "/p2?/(?<vendor>[^/]+)/(?<package>[^/$]+)(?:\\$[a-f0-9]+)?\\.json$"
    );

    /**
     * RegEx pattern for all packages metadata path.
     */
    public static final Pattern ALL_PACKAGES = Pattern.compile("^/packages.json$");

    private final Repository repository;

    /**
     * Client-facing base URL of this repository.
     */
    private final ComposerBaseUrl base;

    /**
     * Link rewriter.
     */
    private final MetadataLinks links;

    /**
     * @param repository Repository.
     * @param base Client-facing base URL of this repository.
     */
    PackageMetadataSlice(final Repository repository, final ComposerBaseUrl base) {
        this.repository = repository;
        this.base = base;
        this.links = new MetadataLinks(base.repository());
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        // GET requests should have empty body, but we must consume it to complete the request
        final String path = line.uri().getPath();
        return body.asBytesFuture().thenCompose(ignored ->
            this.packages(path)
                .toCompletableFuture()
                .thenApply(
                    opt -> opt.map(
                        packages -> packages.content()
                            .thenCompose(Content::asBytesFuture)
                            .thenApply(
                                stored -> ResponseBuilder.ok()
                                    .varyHeader(this.base.vary(headers))
                                    .body(this.relinked(path, stored, headers))
                                    .build()
                            )
                    ).orElse(
                        CompletableFuture.completedFuture(
                            ResponseBuilder.notFound().build()
                        )
                    )
                ).thenCompose(Function.identity())
        );
    }

    /**
     * Re-root the links of a stored document at the base resolved for the
     * request; a document that is not valid JSON is served as stored.
     *
     * @param path Request path
     * @param stored Stored document
     * @param headers Request headers
     * @return Response body
     */
    private byte[] relinked(final String path, final byte[] stored, final Headers headers) {
        final String resolved = this.base.resolve(headers);
        try {
            return ALL_PACKAGES.matcher(path).matches()
                ? this.links.root(stored, resolved)
                : this.links.packages(stored, resolved);
        } catch (final JsonException ex) {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Stored Composer metadata is not valid JSON, serving it without re-rooting its links")
                .eventCategory("web")
                .eventAction("composer_metadata_relink")
                .eventOutcome("failure")
                .field("repository.name", this.base.repository())
                .field("url.path", path)
                .error(ex)
                .field("log.source", "application")
                .log();
            return stored;
        }
    }

    /**
     * Builds key to storage value from path.
     *
     * @param path Resource path.
     * @return Key to storage value.
     */
    private CompletionStage<Optional<Packages>> packages(final String path) {
        final Matcher matcher = PACKAGE.matcher(path);
        if (matcher.find()) {
            return this.repository.packages(
                new Name(matcher.group("vendor") +'/' + matcher.group("package"))
            );
        }
        if (ALL_PACKAGES.matcher(path).matches()) {
            return this.repository.packages();
        }
        throw new IllegalStateException("Unexpected path: "+path);
    }
}
