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
package com.auto1.pantera.composer;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.ClientBaseUrl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Client-facing base URL a hosted Composer repository roots its emitted links
 * at ({@code dist.url}, {@code metadata-url}). Always ends with a repository
 * segment, because Composer links are repository-relative.
 *
 * <p>Resolution order, per request (same tiers as the hosted npm
 * {@code RepoBaseUrl}):</p>
 * <ol>
 *   <li>the base {@code SliceByPath} stamped for the repository the client
 *   actually addressed. Through a group this is the <em>group's</em> base, so
 *   a hosted member emits links under the group;</li>
 *   <li>this repository's own configured {@code url:}, for callers wired
 *   without {@code SliceByPath} in front of them;</li>
 *   <li>the request's own origin plus the repository name, so a repository
 *   with no {@code url:} still emits usable absolute links.</li>
 * </ol>
 *
 * @since 2.2.10
 */
public final class ComposerBaseUrl {

    /**
     * Configured {@code url:}, normalised to end with the repository name.
     */
    private final Optional<String> configured;

    /**
     * Repository name without surrounding slashes.
     */
    private final String repo;

    /**
     * Ctor.
     *
     * @param configured Configured {@code url:}, or empty when absent
     * @param repo Repository name
     */
    public ComposerBaseUrl(final Optional<String> configured, final String repo) {
        this.repo = ComposerBaseUrl.trimSlashes(repo);
        this.configured = configured.map(
            url -> ComposerBaseUrl.withRepository(url, Optional.of(this.repo))
        );
    }

    /**
     * Repository name the base URLs end with.
     *
     * @return Repository name without surrounding slashes
     */
    public String repository() {
        return this.repo;
    }

    /**
     * Resolve the client-facing base for one request.
     *
     * @param headers Request headers
     * @return Absolute base URL without a trailing slash
     */
    public String resolve(final Headers headers) {
        final ClientBaseUrl client = new ClientBaseUrl(headers);
        final String base = client.stamped()
            .or(() -> this.configured)
            .orElseGet(() -> ComposerBaseUrl.withRepository(client.origin(), Optional.of(this.repo)));
        return base.replaceAll("/+$", "");
    }

    /**
     * {@code Vary} value for a response whose body embeds the resolved base.
     *
     * @param headers Request headers
     * @return Vary header value, empty when nothing about the request matters
     */
    public String vary(final Headers headers) {
        return new ClientBaseUrl(headers).varyHeaderValue();
    }

    /**
     * Ensure a base URL ends with the repository name as its last path segment.
     *
     * @param base Base URL
     * @param repo Repository name
     * @return Base URL ending with the repository segment, or {@code base}
     *  unchanged when it is not a valid URI or no name is given
     */
    public static String withRepository(final String base, final Optional<String> repo) {
        final String name = repo.map(ComposerBaseUrl::trimSlashes).orElse("");
        if (name.isEmpty()) {
            return base;
        }
        try {
            final URI uri = new URI(base);
            final List<String> segments = new ArrayList<>();
            final String path = uri.getPath();
            if (path != null) {
                for (final String segment : path.split("/")) {
                    if (!segment.isEmpty()) {
                        segments.add(segment);
                    }
                }
            }
            if (segments.isEmpty() || !segments.get(segments.size() - 1).equals(name)) {
                segments.add(name);
            }
            return new URI(
                uri.getScheme(),
                uri.getUserInfo(),
                uri.getHost(),
                uri.getPort(),
                "/" + String.join("/", segments),
                uri.getQuery(),
                uri.getFragment()
            ).toString();
        } catch (final URISyntaxException ex) {
            return base;
        }
    }

    /**
     * Remove leading and trailing slashes and whitespace.
     *
     * @param value Value
     * @return Trimmed value
     */
    private static String trimSlashes(final String value) {
        return value.trim().replaceAll("^/+", "").replaceAll("/+$", "");
    }
}
