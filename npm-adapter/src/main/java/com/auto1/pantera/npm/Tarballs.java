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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.body.JsonStringRewrite;
import java.net.URL;
import java.util.Map;

/**
 * Roots every tarball reference of a packument at an absolute prefix:
 * {@code /@scope/package-name -> http://host:port/base-path/@scope/package-name}.
 *
 * <p>The packument streams through a {@link JsonStringRewrite}: only the
 * {@code versions/*}{@code /dist/tarball} strings are touched, nothing is
 * buffered or parsed into a document model.</p>
 *
 * @since 0.6
 */
public final class Tarballs {

    /**
     * Rule path of every version's tarball.
     */
    private static final String TARBALL = "versions/*/dist/tarball";

    /**
     * Original content.
     */
    private final Content original;

    /**
     * Absolute URL prefix the rewritten tarball links are rooted at.
     */
    private final String prefix;

    /**
     * Ctor.
     *
     * @param original Original content
     * @param prefix Prefix URL
     */
    public Tarballs(final Content original, final URL prefix) {
        this(original, prefix.toString());
    }

    /**
     * Ctor.
     *
     * @param original Original content
     * @param prefix Absolute URL prefix, with or without a trailing slash
     */
    public Tarballs(final Content original, final String prefix) {
        this.original = original;
        this.prefix = prefix;
    }

    /**
     * The packument with every tarball rooted at the prefix.
     *
     * @return Rewritten content, streamed
     */
    public Content value() {
        return new JsonStringRewrite(
            this.original,
            Map.of(Tarballs.TARBALL, tarball -> Tarballs.rewriteTarball(tarball, this.prefix))
        );
    }

    /**
     * Rewrite a single tarball reference (absolute or relative, however it
     * was stored) into an absolute URL under the given prefix. Shared by
     * {@link #updateJson} (full packument, one tarball per version) and
     * {@link com.auto1.pantera.npm.http.SingleVersionSlice} (one manifest,
     * a single tarball) so both paths apply the exact same URL-relativizing
     * rules.
     *
     * @param tarballPath The tarball reference as stored (absolute URL or
     *  path fragment)
     * @param prefix Absolute URL prefix to rebuild the link under
     * @return Absolute tarball URL rooted at {@code prefix}
     */
    public static String rewriteTarball(final String tarballPath, final String prefix) {
        // Ensure prefix doesn't end with slash for consistent concatenation
        final String cleanPrefix = prefix.replaceAll("/$", "");
        String path = tarballPath;
        // Strip absolute URL if present (handles already-malformed URLs from old metadata)
        if (path.startsWith("http://") || path.startsWith("https://")) {
            try {
                final java.net.URI uri = new java.net.URI(path);
                path = uri.getPath();
            } catch (final java.net.URISyntaxException ex) {
                // Fallback: extract path after host
                final int pathStart = path.indexOf('/', path.indexOf("://") + 3);
                if (pathStart > 0) {
                    path = path.substring(pathStart);
                }
            }
        }
        // Extract package-relative path using TgzRelativePath
        // This handles paths like /test_prefix/api/npm/@scope/pkg/-/@scope/pkg-1.0.0.tgz
        // and extracts just @scope/pkg/-/@scope/pkg-1.0.0.tgz
        try {
            path = new TgzRelativePath(path).relative();
        } catch (final com.auto1.pantera.PanteraException ex) { // NOPMD EmptyCatchBlock - intentional: unparseable tarball paths fall through and are used as-is to preserve backward compatibility
            // If TgzRelativePath can't parse it, use as-is
            // This preserves backward compatibility
        }
        // Ensure tarball path starts with slash
        final String cleanTarball = path.startsWith("/") ? path : "/" + path;
        return cleanPrefix + cleanTarball;
    }
}
