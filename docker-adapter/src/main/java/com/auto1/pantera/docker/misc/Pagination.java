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
            params.value("n").map(Integer::parseInt).orElse(Integer.MAX_VALUE)
        );
    }

    public static Pagination from(String repoName, Integer limit) {
        return new Pagination(
            repoName, limit != null ? limit : Integer.MAX_VALUE
        );
    }

    /**
     * Applies pagination to a stream of values, reporting whether the result was
     * truncated (more entries exist beyond this page) and the cursor to resume
     * pagination from — used to emit a {@code Link: rel="next"} header on
     * {@code tags/list} and {@code _catalog} when a page does not contain the
     * full result set (Docker Distribution spec pagination).
     *
     * @param stream Values to paginate.
     * @return Paginated page: JSON array, truncation flag, and resume cursor.
     */
    public Page page(Stream<String> stream) {
        final List<String> filtered = stream.filter(this::lessThan).sorted().distinct().toList();
        final boolean truncated = filtered.size() > this.limit;
        final List<String> slice = truncated ? filtered.subList(0, this.limit) : filtered;
        final JsonArrayBuilder json = Json.createArrayBuilder();
        slice.forEach(json::add);
        return new Page(
            json,
            truncated,
            slice.isEmpty() ? Optional.empty() : Optional.of(slice.get(slice.size() - 1))
        );
    }

    /**
     * Result of {@link #page(Stream)}: the current page's JSON array, whether more
     * entries exist beyond it, and the cursor (last value on this page) to resume from.
     *
     * @param json JSON array of the current page's values.
     * @param truncated True when more entries exist beyond this page.
     * @param cursor Last value on this page, empty when the page itself is empty.
     */
    public record Page(JsonArrayBuilder json, boolean truncated, Optional<String> cursor) {
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
