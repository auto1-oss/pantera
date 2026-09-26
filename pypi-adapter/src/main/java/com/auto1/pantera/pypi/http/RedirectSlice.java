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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ClientBaseUrl;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;
import com.auto1.pantera.pypi.NormalizedProjectName;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Slice to redirect to the PEP 503 normalized project URL.
 *
 * <p>The Location keeps the client-facing path: when an outer slice stamped
 * the repository's client-facing base ({@link ClientBaseUrl#HEADER}, which
 * carries the global path prefix and the {@code /api/<type>/} route style)
 * the target is that base plus the repository-relative path; otherwise the
 * pre-trim path ({@code X-FullPath}) or the request path is used. The
 * normalized project URL always ends with a slash, as PEP 503 requires.</p>
 *
 * @since 0.6
 */
public final class RedirectSlice implements Slice {

    /**
     * Full path header name.
     */
    private static final String HDR_FULL_PATH = "X-FullPath";

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final Optional<String> full = new RqHeaders(headers, RedirectSlice.HDR_FULL_PATH)
            .stream().findFirst();
        final Optional<String> base = new RqHeaders(headers, ClientBaseUrl.HEADER)
            .stream().findFirst().filter(value -> !value.isBlank());
        final String location;
        if (base.isPresent()) {
            location = RedirectSlice.trimTrailingSlash(base.get()) + RedirectSlice.normalized(
                full.map(RedirectSlice::withoutRepoSegment).orElse(line.uri().getPath())
            );
        } else {
            location = RedirectSlice.normalized(full.orElse(line.uri().getPath()));
        }
        return body.asBytesFuture().thenApply(
            ignored -> ResponseBuilder.movedPermanently().header("Location", location).build()
        );
    }

    /**
     * Replace the last path segment with its normalized project name and end
     * the path with a slash.
     * @param path Path
     * @return Normalized path
     */
    private static String normalized(final String path) {
        final String trimmed = RedirectSlice.trimTrailingSlash(path);
        final int slash = trimmed.lastIndexOf('/');
        final String last = trimmed.substring(slash + 1);
        return trimmed.substring(0, slash + 1)
            + new NormalizedProjectName.Simple(last).value() + "/";
    }

    /**
     * Drop the leading repository-name segment of a pre-trim path
     * ({@code /<repo>/simple/Foo/} to {@code /simple/Foo/}).
     * @param path Pre-trim path
     * @return Repository-relative path
     */
    private static String withoutRepoSegment(final String path) {
        final int next = path.indexOf('/', 1);
        final String result;
        if (path.startsWith("/") && next > 0) {
            result = path.substring(next);
        } else {
            result = path;
        }
        return result;
    }

    /**
     * Remove trailing slashes.
     * @param value Value
     * @return Value without trailing slashes
     */
    private static String trimTrailingSlash(final String value) {
        return value.replaceAll("/+$", "");
    }
}
