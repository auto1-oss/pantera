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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ext.KeyLastPart;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.ContentFileName;
import com.auto1.pantera.http.headers.Header;

import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Artifact response headers for {@code GET} and {@code HEAD} requests.
 * <p>
 * Maven client supports {@code X-Checksum-*} headers for different hash algorithms,
 * {@code ETag} header for caching, {@code Content-Type} and {@code Content-Disposition}.
 */
final class ArtifactHeaders {

    /**
     * Headers from artifact key and checksums.
     * @param location Artifact location
     * @param checksums Artifact checksums
     */
    public static Headers from(Key location, Map<String, String> checksums) {
        return new Headers()
            .add(contentDisposition(location))
            .add(contentType(location))
            .addAll(new Headers(checksumsHeader(checksums)));
    }

    /**
     * Content disposition header.
     * @param location Artifact location
     * @return Headers with content disposition
     */
    private static Header contentDisposition(final Key location) {
        return new ContentFileName(new KeyLastPart(location).get());
    }

    /**
     * Checksum headers.
     * @param checksums Artifact checksums
     * @return Checksum header and {@code ETag} header
     */
    private static List<Header> checksumsHeader(final Map<String, String> checksums) {
        List<Header> res = new ArrayList<>(checksums.size() + 1);
        res.addAll(
            checksums.entrySet()
                .stream()
                .map(entry -> new Header("X-Checksum-" + entry.getKey(), entry.getValue()))
                .toList()
        );
        String sha1 = checksums.get("sha1");
        if (sha1 != null && !sha1.isEmpty()) {
            res.add(new Header("ETag", sha1));
        }
        return res;
    }

    /**
     * Artifact content type header.
     * @param key Artifact key
     * @return Content type header
     */
    static Header contentType(final Key key) {
        // "*" is not a media type; responses also carry nosniff, so an
        // unknown file is announced as opaque bytes.
        return new Header(
            "Content-Type",
            mavenType(key)
                .or(() -> Optional.ofNullable(URLConnection.guessContentTypeFromName(key.string())))
                .orElse("application/octet-stream")
        );
    }

    /**
     * The Content-Type Pantera assigns to a Maven file kind itself (archives,
     * POMs, Gradle Module Metadata, checksums, signatures), whatever an
     * upstream announced for it.
     * @param key Artifact key
     * @return Maven content type, empty for other files
     */
    static Optional<String> mavenType(final Key key) {
        return Optional.ofNullable(
            switch (extension(key)) {
                case "jar" -> "application/java-archive";
                case "pom" -> "application/x-maven-pom+xml";
                // Gradle Module Metadata is a JSON document.
                case "module" -> "application/json";
                case "md5", "sha1", "sha256", "sha512" -> "text/plain";
                case "asc" -> "application/pgp-signature";
                default -> null;
            }
        );
    }

    /**
     * Artifact extension.
     * @param key Artifact key
     * @return Lowercased extension without dot char.
     */
    private static String extension(final Key key) {
        final String src = key.string();
        return src.substring(src.lastIndexOf('.') + 1).toLowerCase(Locale.US);
    }
}
