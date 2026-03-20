/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
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
        // Ensure prefix doesn't end with slash for consistent concatenation
        final String cleanPrefix = prefix.replaceAll("/$", "");
        for (final String version : versions) {
            String tarballPath = original.getJsonObject("versions").getJsonObject(version)
                .getJsonObject("dist").getString("tarball");
            
            // Strip absolute URL if present (handles already-malformed URLs from old metadata)
            if (tarballPath.startsWith("http://") || tarballPath.startsWith("https://")) {
                try {
                    final java.net.URI uri = new java.net.URI(tarballPath);
                    tarballPath = uri.getPath();
                } catch (final java.net.URISyntaxException ex) {
                    // Fallback: extract path after host
                    final int pathStart = tarballPath.indexOf('/', tarballPath.indexOf("://") + 3);
                    if (pathStart > 0) {
                        tarballPath = tarballPath.substring(pathStart);
                    }
                }
            }
            
            // Extract package-relative path using TgzRelativePath
            // This handles paths like /test_prefix/api/npm/@scope/pkg/-/@scope/pkg-1.0.0.tgz
            // and extracts just @scope/pkg/-/@scope/pkg-1.0.0.tgz
            try {
                tarballPath = new TgzRelativePath(tarballPath).relative();
            } catch (final com.auto1.pantera.PanteraException ex) {
                // If TgzRelativePath can't parse it, use as-is
                // This preserves backward compatibility
            }
            
            // Ensure tarball path starts with slash
            final String cleanTarball = tarballPath.startsWith("/") ? tarballPath : "/" + tarballPath;
            builder.add(
                String.format("/versions/%s/dist/tarball", version),
                cleanPrefix + cleanTarball
            );
        }
        return builder.build().apply(original);
    }
}
