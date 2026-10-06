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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.body.JsonStringRewrite;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links of served Composer metadata, re-rooted at the base resolved for the
 * request ({@link ComposerBaseUrl}) as the stored bytes stream through.
 *
 * <p>Nothing is buffered or parsed into a document: a
 * {@link JsonStringRewrite} rewrites the {@code dist.url} of every version,
 * or the {@code metadata-url} and {@code available-packages-url} of the root
 * {@code packages.json}, and passes every other byte through as stored.</p>
 *
 * <p>A stored {@code dist.url} is "ours" when it is repository-relative (how
 * uploads are stored without a configured {@code url:}) or an absolute URL
 * whose path reaches an archive ({@code .zip}, {@code .tar.gz}, {@code .tgz})
 * through a segment named like this repository, whatever host and path
 * prefix it was frozen under: an older {@code url:}, a JFrog-style
 * {@code /api/composer/} route, a global prefix of any depth. Anything else
 * (a GitHub zipball, another repository on the same host) is left as is.
 * The one shape that cannot be told apart is an archive URL on a foreign
 * host whose path segment named like this repository is followed by an
 * archive path (a GitHub organisation called like the repository serving
 * {@code .zip} downloads); it is re-rooted like a legacy host would be.</p>
 *
 * @since 2.2.10
 */
public final class MetadataLinks {

    /**
     * Legacy alias of the archive path prefix; maps to the same storage key.
     */
    private static final String DIRECT_DISTS = "direct-dists/";

    /**
     * Rule path of every version's dist URL, versions keyed by name (v1) or
     * listed in an array (v2).
     */
    private static final String DIST_URL = "packages/*/*/dist/url";

    /**
     * Path of an absolute archive URL served by this repository: any prefix,
     * the repository segment, then the archive path inside the repository.
     */
    private final Pattern own;

    /**
     * Ctor.
     *
     * @param repo Repository name without surrounding slashes
     */
    public MetadataLinks(final String repo) {
        this.own = Pattern.compile(
            "^(?:/.*?)?/" + Pattern.quote(repo) + "/(.+\\.(?:zip|tar\\.gz|tgz))$"
        );
    }

    /**
     * Re-root every {@code dist.url} of a per-package metadata document.
     *
     * @param stored Stored document
     * @param base Resolved base URL ending with a repository segment
     * @return Document with re-rooted dist URLs, streamed
     */
    public Content packages(final Content stored, final String base) {
        return new JsonStringRewrite(
            stored, Map.of(MetadataLinks.DIST_URL, url -> this.dist(url, base))
        );
    }

    /**
     * Re-root the links of the root {@code packages.json}.
     *
     * @param stored Stored document
     * @param base Resolved base URL ending with a repository segment
     * @return Document whose metadata links point under {@code base}, streamed
     */
    public Content root(final Content stored, final String base) {
        return new JsonStringRewrite(
            stored,
            Map.of(
                "metadata-url", ignored -> base + "/p2/%package%.json",
                "available-packages-url", ignored -> base + "/p2/available-packages.json"
            )
        );
    }

    /**
     * Re-root one {@code dist.url}.
     *
     * @param url Stored URL
     * @param base Resolved base URL ending with a repository segment
     * @return URL under {@code base}, or {@code url} when it points elsewhere
     */
    String dist(final String url, final String base) {
        return this.pathInRepository(url)
            .map(path -> path.startsWith(MetadataLinks.DIRECT_DISTS)
                ? path.substring(MetadataLinks.DIRECT_DISTS.length()) : path)
            .filter(path -> !path.isEmpty())
            .map(path -> base + "/" + path)
            .orElse(url);
    }

    /**
     * Path of a stored URL relative to this repository.
     *
     * @param url Stored URL
     * @return Path inside the repository, empty when the URL points elsewhere
     */
    private Optional<String> pathInRepository(final String url) {
        if (!url.contains("://")) {
            return Optional.of(url.replaceFirst("^/+", ""));
        }
        final URI uri;
        try {
            uri = new URI(url);
        } catch (final URISyntaxException ex) {
            return Optional.empty();
        }
        if (uri.getRawPath() == null) {
            return Optional.empty();
        }
        final Matcher matcher = this.own.matcher(uri.getRawPath());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        final String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return Optional.of(matcher.group(1) + query);
    }
}
