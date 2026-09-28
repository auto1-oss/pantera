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
package com.auto1.pantera.npm;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.ClientBaseUrl;

import java.net.URL;
import java.util.Optional;

/**
 * Client-facing base URL a hosted npm repository roots its emitted links at
 * ({@code dist.tarball}, the {@code .npmrc} registry line).
 *
 * <p>Resolution order, per request:</p>
 * <ol>
 *   <li>the base {@code SliceByPath} stamped for the repository the client
 *   actually addressed — which is that repository's configured {@code url:}
 *   when it has one, else the canonical {@code client_base_url} admin
 *   setting, else the request-derived origin. Taking the stamp first is what
 *   makes a group member emit the <em>group's</em> URLs rather than its
 *   own;</li>
 *   <li>this repository's own configured {@code url:}, for callers wired
 *   without {@code SliceByPath} in front of them (unit tests, embedded
 *   use);</li>
 *   <li>the request's own origin, so a repository with no {@code url:} at all
 *   still emits usable absolute links.</li>
 * </ol>
 *
 * <p>Tier 3 is why {@code url:} is optional for hosted npm repositories:
 * before 2.2.6 the adapter took a bare {@link URL} and {@code
 * RepositorySlices} therefore had to call {@code RepoConfig#url()}, which
 * throws when the key is absent — so a hosted npm repository could not be
 * configured without pinning every client's tarball links to one host.</p>
 *
 * @since 2.2.6
 */
public final class RepoBaseUrl {

    /**
     * This repository's configured {@code url:}, or empty when it has none.
     */
    private final Optional<URL> configured;

    /**
     * Ctor.
     *
     * @param configured Configured {@code url:}, or empty when absent
     */
    public RepoBaseUrl(final Optional<URL> configured) {
        this.configured = configured;
    }

    /**
     * Resolve the client-facing base for one request.
     *
     * @param headers Request headers
     * @return Absolute base URL, never {@code null}
     */
    public String resolve(final Headers headers) {
        final ClientBaseUrl client = new ClientBaseUrl(headers);
        return client.stamped()
            .or(() -> this.configured.map(URL::toString))
            .orElseGet(client::origin);
    }

    /**
     * {@code Vary} value for a response whose body embeds the resolved base —
     * empty when nothing about the request can influence it.
     *
     * @param headers Request headers
     * @return Vary header value
     */
    public String vary(final Headers headers) {
        return new ClientBaseUrl(headers).varyHeaderValue();
    }
}
