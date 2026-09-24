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
package com.auto1.pantera.docker.misc;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.docker.error.PaginationNumberInvalidException;
import com.auto1.pantera.http.rq.RqParams;
import org.apache.hc.core5.net.URIBuilder;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Pagination parameters.
 *
 * @param last  last
 * @param limit
 */
public record Pagination(String last, int limit) {

    public static Pagination empty() {
        return from(null, null);
    }

    public static Pagination from(URI uri) {
        final RqParams params = new RqParams(uri);
        return new Pagination(
            params.value("last").orElse(null),
            params.value("n").map(Pagination::size).orElse(Integer.MAX_VALUE)
        );
    }

    /**
     * Parse the {@code n} page size.
     *
     * @param value Page size as sent
     * @return Page size
     * @throws PaginationNumberInvalidException When it is not a non-negative
     *  integer (answered 400, not 500)
     */
    private static int size(final String value) {
        final int size;
        try {
            size = Integer.parseInt(value);
        } catch (final NumberFormatException ex) {
            throw new PaginationNumberInvalidException(value, ex);
        }
        if (size < 0) {
            throw new PaginationNumberInvalidException(value);
        }
        return size;
    }

    public static Pagination from(String repoName, Integer limit) {
        return new Pagination(
            repoName, limit != null ? limit : Integer.MAX_VALUE
        );
    }

    public JsonArrayBuilder apply(Stream<String> stream) {
        final JsonArrayBuilder res = Json.createArrayBuilder();
        stream.filter(this::lessThan)
            .sorted()
            .distinct()
            .limit(this.limit())
            .forEach(res::add);
        return res;
    }

    /**
     * Creates a URI string with pagination parameters.
     *
     * @param uriString a valid URI in string form.
     * @return URI string with pagination parameters.
     */
    public String uriWithPagination(String uriString) {
        try {
            URIBuilder builder = new URIBuilder(uriString);
            if (limit != Integer.MAX_VALUE) {
                builder.addParameter("n", String.valueOf(limit));
            }
            if (last != null) {
                builder.addParameter("last", last);
            }
            return builder.toString();
        } catch (URISyntaxException e) {
            throw new PanteraException(e);
        }
    }

    /**
     * The {@code Link: <...>; rel="next"} header value for a page served
     * with these parameters: present when a page size was requested and the
     * page is full, pointing after the page's last entry.
     *
     * @param path Path the client requested (the next page's path)
     * @param page Entries of the served page, in order
     * @return Link header value, empty when there is no next page to link
     */
    public Optional<String> nextLink(final String path, final List<String> page) {
        Optional<String> link = Optional.empty();
        if (this.limit > 0 && this.limit != Integer.MAX_VALUE && page.size() >= this.limit) {
            link = Optional.of(
                String.format(
                    "<%s>; rel=\"next\"",
                    new Pagination(page.get(page.size() - 1), this.limit)
                        .uriWithPagination(path)
                )
            );
        }
        return link;
    }

    /**
     * Compares {@code name} and {@code Pagination.last} values.
     * If {@code Pagination.last} than returns {@code true}, else it
     * compares {@code name} and {@code Pagination.last} values.
     * If {@code name} value more than {@code Pagination.last} value returns {@code true}.
     *
     * @param name Image repository name.
     * @return True if given {@code name} more than {@code Pagination.last}.
     */
    private boolean lessThan(String name) {
        return last == null || name.compareTo(last) > 0;
    }
}
