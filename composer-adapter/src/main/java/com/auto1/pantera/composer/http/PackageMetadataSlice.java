/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.composer.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.composer.Name;
import com.auto1.pantera.composer.Packages;
import com.auto1.pantera.composer.Repository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slice that serves package metadata.
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
     * @param repository Repository.
     */
    PackageMetadataSlice(final Repository repository) {
        this.repository = repository;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        // GET requests should have empty body, but we must consume it to complete the request
        return body.asBytesFuture().thenCompose(ignored ->
            this.packages(line.uri().getPath())
                .toCompletableFuture()
                .thenApply(
                    opt -> opt.map(
                        packages -> packages.content()
                            .thenApply(cnt -> ResponseBuilder.ok().body(cnt).build())
                    ).orElse(
                        CompletableFuture.completedFuture(
                            ResponseBuilder.notFound().build()
                        )
                    )
                ).thenCompose(Function.identity())
        );
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
