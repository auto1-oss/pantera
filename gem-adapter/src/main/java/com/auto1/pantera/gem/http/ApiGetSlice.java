/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.gem.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.gem.Gem;
import com.auto1.pantera.http.PanteraHttpException;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.RsStatus;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Returns some basic information about the given gem.
 * <p>
 * Handle {@code GET - /api/v1/gems/[GEM NAME].(json|yaml)}
 * requests, see
 * <a href="https://guides.rubygems.org/rubygems-org-api">RubyGems API</a>
 * for documentation.
 * </p>
 *
 * @since 0.2
 */
final class ApiGetSlice implements Slice {

    /**
     * Endpoint path pattern.
     */
    public static final Pattern PATH_PATTERN = Pattern
        .compile("/api/v1/gems/(?<name>[\\w\\d-]+).(?<fmt>json|yaml)");

    /**
     * Gem SDK.
     */
    private final Gem sdk;

    /**
     * New slice for handling Get API requests.
     * @param storage Gems storage
     */
    ApiGetSlice(final Storage storage) {
        this.sdk = new Gem(storage);
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final Matcher matcher = PATH_PATTERN.matcher(line.uri().toString());
        if (!matcher.find()) {
            throw new PanteraHttpException(
                RsStatus.BAD_REQUEST, String.format("Invalid URI: `%s`", matcher)
            );
        }
        return this.sdk.info(matcher.group("name"))
            .thenApply(MetaResponseFormat.byName(matcher.group("fmt")))
            .toCompletableFuture();
    }
}
