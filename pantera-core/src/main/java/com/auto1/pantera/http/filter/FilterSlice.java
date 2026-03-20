/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.filter;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.ResponseBuilder;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Slice that filters content of repository.
 */
public class FilterSlice implements Slice {

    private final Slice origin;

    /**
     * Filter engine.
     */
    private final Filters filters;

    /**
     * @param origin Origin slice
     * @param yaml Yaml mapping to read filters from
     */
    public FilterSlice(final Slice origin, final YamlMapping yaml) {
        this(
            origin,
            Optional.of(yaml.yamlMapping("filters"))
                .map(Filters::new)
                .get()
        );
    }

    /**
     * @param origin Origin slice
     * @param filters Filters
     */
    public FilterSlice(final Slice origin, final Filters filters) {
        this.origin = origin;
        this.filters = Objects.requireNonNull(filters);
    }

    @Override
    public final CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body
    ) {
        if (this.filters.allowed(line, headers)) {
            return this.origin.response(line, headers, body);
        }
        // Consume request body to prevent Vert.x request leak
        return body.asBytesFuture().thenApply(ignored ->
            ResponseBuilder.forbidden().build()
        );
    }
}
