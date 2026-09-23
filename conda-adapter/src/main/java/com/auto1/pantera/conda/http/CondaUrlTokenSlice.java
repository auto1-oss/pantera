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
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Presents a conda URL token ({@code /t/<token>/...}) as an
 * {@code Authorization: token <token>} header.
 *
 * <p>conda and anaconda-client put the token in the channel URL and send no
 * {@code Authorization} header, so the repository's anonymous-access gate
 * (which only looks at that header) rejected every {@code /t/} request of a
 * private channel. This slice wraps the repository <em>outside</em> that
 * gate. It grants nothing itself: the conda routes still validate the
 * token. An explicit {@code Authorization} header always wins.</p>
 *
 * @since 2.2.9
 */
public final class CondaUrlTokenSlice implements Slice {

    /**
     * Token path on a dedicated port: {@code /t/<token>/...}.
     */
    private static final Pattern BARE = Pattern.compile("^/t/([^/]+)/.+$");

    /**
     * Token path on the main port: {@code /<repo>/t/<token>/...}.
     */
    private static final Pattern PREFIXED = Pattern.compile("^/[^/]+/t/([^/]+)/.+$");

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Token path pattern.
     */
    private final Pattern pattern;

    /**
     * Ctor.
     * @param origin Origin slice
     * @param prefixed Whether request paths start with the repository name
     *  (main port) rather than being served at the root of a dedicated port
     */
    public CondaUrlTokenSlice(final Slice origin, final boolean prefixed) {
        this.origin = origin;
        if (prefixed) {
            this.pattern = CondaUrlTokenSlice.PREFIXED;
        } else {
            this.pattern = CondaUrlTokenSlice.BARE;
        }
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final Matcher matcher = this.pattern.matcher(line.uri().getPath());
        final Headers effective;
        if (matcher.matches() && headers.values(Authorization.NAME).isEmpty()) {
            effective = headers.copy().add(
                Authorization.NAME,
                String.format("%s %s", TokenAuthScheme.NAME, matcher.group(1))
            );
        } else {
            effective = headers;
        }
        return this.origin.response(line, effective, body);
    }
}
