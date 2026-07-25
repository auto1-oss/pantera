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

import com.auto1.pantera.asto.Concatenation;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Remaining;
import io.reactivex.Flowable;
import java.io.StringReader;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonPatchBuilder;

/**
 * Prepends all tarball references in the package metadata json with the prefix to build
 * absolute URL: /@scope/package-name -&gt; http://host:port/base-path/@scope/package-name.
 * @since 0.6
 */
public final class Tarballs {

    /**
     * Original content.
     */
    private final Content original;

    /**
     * URL prefix.
     */
    private final URL prefix;

    /**
     * Ctor.
     * @param original Original content
     * @param prefix URL prefix
     */
    public Tarballs(final Content original, final URL prefix) {
        this.original = original;
        this.prefix = prefix;
    }

    /**
     * Return modified content with prepended URLs.
     * @return Modified content with prepended URLs
     */
    public Content value() {
        // OPTIMIZATION: Use size hint for efficient pre-allocation
        final long knownSize = this.original.size().orElse(-1L);
        return new Content.From(
            Concatenation.withSize(this.original, knownSize)
                .single()
                .map(buf -> new Remaining(buf).bytes())
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .map(json -> Json.createReader(new StringReader(json)).readObject())
                .map(json -> Tarballs.updateJson(json, this.prefix.toString()))
                .flatMapPublisher(
                    json -> new Content.From(
                        Flowable.fromArray(
                            ByteBuffer.wrap(
                                json.toString().getBytes(StandardCharsets.UTF_8)
                            )
                        )
                    )
                )
        );
    }

    /**
     * Replaces tarball links with absolute paths based on prefix.
     * @param original Original JSON object
     * @param prefix Links prefix
     * @return Transformed JSON object
     */
    private static JsonObject updateJson(final JsonObject original, final String prefix) {
        final JsonPatchBuilder builder = Json.createPatchBuilder();
        final Set<String> versions = original.getJsonObject("versions").keySet();
        for (final String version : versions) {
            final String tarballPath = original.getJsonObject("versions").getJsonObject(version)
                .getJsonObject("dist").getString("tarball");
            builder.add(
                String.format("/versions/%s/dist/tarball", version),
                Tarballs.rewriteTarball(tarballPath, prefix)
            );
        }
        return builder.build().apply(original);
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
