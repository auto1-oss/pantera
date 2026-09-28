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
package com.auto1.pantera.conda.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.conda.http.auth.TokenAuthScheme;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Accepts the conda CLI's placement of a channel token.
 *
 * <p>Given the channel {@code https://host/<path>/t/<token>}, conda strips the
 * token from the channel and re-inserts it right after the host, so every
 * request arrives as {@code /t/<token>/<path>/...}, in front of any global
 * prefix and the repository name. This slice, mounted in front of the
 * server's routing, removes that segment and presents the token as an
 * {@code Authorization: token <token>} header, which the conda routes
 * validate. It only acts on a segment that carries a JWT (raw or
 * hex-encoded, see {@link CondaUrlToken}), so the paths of a repository that
 * happens to be named {@code t} are left alone. An explicit
 * {@code Authorization} header always wins; nothing is granted here.</p>
 *
 * @since 2.2.9
 */
public final class CondaRootTokenSlice implements Slice {

    /**
     * Token in front of the path: {@code /t/<token>/<rest>}.
     */
    private static final Pattern ROOT = Pattern.compile("^/t/([^/]+)(/.+)$");

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Ctor.
     * @param origin Origin slice
     */
    public CondaRootTokenSlice(final Slice origin) {
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final Matcher matcher = CondaRootTokenSlice.ROOT.matcher(line.uri().getRawPath());
        final CompletableFuture<Response> res;
        if (matcher.matches() && new CondaUrlToken(matcher.group(1)).jwt()) {
            final Headers effective;
            if (headers.values(Authorization.NAME).isEmpty()) {
                effective = headers.copy().add(
                    Authorization.NAME,
                    String.format(
                        "%s %s", TokenAuthScheme.NAME,
                        new CondaUrlToken(matcher.group(1)).value()
                    )
                );
            } else {
                effective = headers;
            }
            final String query = line.uri().getRawQuery();
            final StringBuilder uri = new StringBuilder(matcher.group(2));
            if (query != null && !query.isEmpty()) {
                uri.append('?').append(query);
            }
            res = this.origin.response(
                new RequestLine(line.method(), URI.create(uri.toString()), line.version()),
                effective, body
            );
        } else {
            res = this.origin.response(line, headers, body);
        }
        return res;
    }
}
