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
package com.auto1.pantera.composer.http.proxy;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonValue;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Rewrites download URLs in Composer metadata to proxy through Pantera.
 * Transforms external URLs (GitHub, CDN) to local proxy URLs.
 *
 * @since 1.0
 */
public final class MetadataUrlRewriter {

    /**
     * Base URL for proxy requests (includes repo path, e.g., "http://localhost:8080/php_proxy").
     */
    private final String baseUrl;

    /**
     * Ctor.
     *
     * @param baseUrl Base URL for the Pantera repository (including repo path)
     */
    public MetadataUrlRewriter(final String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * Rewrite URLs in metadata JSON.
     * Transforms dist.url fields to proxy through this repository.
     *
     * @param metadata Original metadata JSON string
     * @return Rewritten metadata with proxy URLs
     */
    public byte[] rewrite(final String metadata) {
        final JsonObject original = Json.createReader(new StringReader(metadata)).readObject();
        final JsonObjectBuilder builder = Json.createObjectBuilder();

        // Copy all top-level fields
        for (final Map.Entry<String, JsonValue> entry : original.entrySet()) {
            final String key = entry.getKey();
            if ("packages".equals(key)) {
                builder.add(key, this.rewritePackages(original.getJsonObject(key)));
            } else {
                builder.add(key, entry.getValue());
            }
        }

        return builder.build().toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Rewrite every top-level URL field in a Composer repository root
     * document ({@code /packages.json} / {@code /repo.json}) to a
     * Pantera-local equivalent, so a client that follows any URL the
     * root advertises stays inside Pantera's cache / cooldown / auth
     * boundary rather than escaping straight to the upstream.
     *
     * <p>Known fields are rewritten to their Pantera-local shape;
     * {@code notify} / {@code notify-batch} are dropped outright (no
     * publish callback ever escapes to upstream); any other top-level
     * field whose value is an absolute {@code http(s)://} URL is
     * dropped fail-closed rather than passed through, since it is by
     * definition a field this rewriter does not understand. Every
     * other field (e.g. {@code packages}, {@code providers}, informational
     * flags) is passed through unchanged. The nested {@code packages}
     * object still goes through the existing {@link #rewritePackages}
     * dist-URL rewrite — idempotent when the packages were already
     * rewritten upstream of this call (e.g. by a group member).</p>
     *
     * @param metadata Original root JSON string
     * @param repoBaseUrl Pantera-local base to anchor rewritten URLs to
     *  (an absolute URL for a proxy repository, or a host-absolute path
     *  for a group repository — both compose correctly by concatenation)
     * @return Rewritten root JSON, UTF-8 encoded
     */
    public byte[] rewriteRoot(final String metadata, final String repoBaseUrl) {
        final JsonObject original = Json.createReader(new StringReader(metadata)).readObject();
        final JsonObjectBuilder builder = Json.createObjectBuilder();
        for (final Map.Entry<String, JsonValue> entry : original.entrySet()) {
            final String key = entry.getKey();
            final JsonValue value = entry.getValue();
            if ("packages".equals(key)) {
                // The lazy-providers scheme (the common Packagist-mirror
                // shape) advertises an EMPTY ARRAY here, not an object —
                // only inline-packages (Satis) roots use an object. Only
                // route through the object-shaped dist rewrite when it
                // actually is one; anything else (array) passes through.
                if (value.getValueType() == JsonValue.ValueType.OBJECT) {
                    builder.add(key, this.rewritePackages(value.asJsonObject()));
                } else {
                    builder.add(key, value);
                }
            } else if ("metadata-url".equals(key) || "providers-url".equals(key)) {
                builder.add(key, repoBaseUrl + "/p2/%package%.json");
            } else if ("available-packages-url".equals(key)) {
                builder.add(key, repoBaseUrl + "/p2/available-packages.json");
            } else if ("search".equals(key)) {
                builder.add(key, repoBaseUrl + "/packages/list.json?q=%query%&type=%type%");
            } else if ("list".equals(key)) {
                builder.add(key, repoBaseUrl + "/packages/list.json");
            } else if ("notify".equals(key) || "notify-batch".equals(key)) {
                // Dropped intentionally: no publish callback escapes to upstream.
                continue;
            } else if ("security-advisories".equals(key)) {
                builder.add(key, rewriteSecurityAdvisories(value, repoBaseUrl));
            } else if (isAbsoluteUrl(value)) {
                // Fail-closed: an unrecognised top-level field pointing at an
                // absolute URL is dropped rather than leaked to the client.
                continue;
            } else {
                builder.add(key, value);
            }
        }
        return builder.build().toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Rewrite the {@code security-advisories.api-url} sub-field to a
     * Pantera-local endpoint; every other sub-field (e.g. {@code metadata})
     * is preserved unchanged. Non-object values (Composer allows
     * {@code "security-advisories": false} to disable the feature) are
     * passed through unchanged.
     */
    private static JsonValue rewriteSecurityAdvisories(
        final JsonValue value, final String repoBaseUrl
    ) {
        if (value.getValueType() != JsonValue.ValueType.OBJECT) {
            return value;
        }
        final JsonObject advisories = value.asJsonObject();
        if (!advisories.containsKey("api-url")) {
            return value;
        }
        final JsonObjectBuilder out = Json.createObjectBuilder();
        for (final Map.Entry<String, JsonValue> entry : advisories.entrySet()) {
            if ("api-url".equals(entry.getKey())) {
                out.add("api-url", repoBaseUrl + "/api/security-advisories/");
            } else {
                out.add(entry.getKey(), entry.getValue());
            }
        }
        return out.build();
    }

    /**
     * True iff {@code value} is a JSON string holding an absolute
     * {@code http://} or {@code https://} URL.
     */
    private static boolean isAbsoluteUrl(final JsonValue value) {
        if (value.getValueType() != JsonValue.ValueType.STRING) {
            return false;
        }
        final String str = ((JsonString) value).getString();
        return str.startsWith("http://") || str.startsWith("https://");
    }

    /**
     * Rewrite packages object.
     * Handles both v1 format (object with version keys) and v2 minified format (array of packages).
     *
     * @param packages Original packages object
     * @return Rewritten packages object
     */
    private JsonObject rewritePackages(final JsonObject packages) {
        final JsonObjectBuilder packagesBuilder = Json.createObjectBuilder();

        for (final Map.Entry<String, JsonValue> pkgEntry : packages.entrySet()) {
            final String packageName = pkgEntry.getKey();
            final JsonValue pkgValue = pkgEntry.getValue();
            
            // Check if it's v2 minified format (array) or v1 format (object)
            if (pkgValue.getValueType() == JsonValue.ValueType.ARRAY) {
                // V2 minified format: array of package versions
                packagesBuilder.add(packageName, this.rewriteVersionsArray(packageName, pkgValue.asJsonArray()));
            } else {
                // V1 format: object with version keys
                packagesBuilder.add(packageName, this.rewriteVersions(packageName, pkgValue.asJsonObject()));
            }
        }

        return packagesBuilder.build();
    }

    /**
     * Rewrite versions array for a package (v2 minified format).
     *
     * @param packageName Package name
     * @param versions Original versions array
     * @return Rewritten versions array
     */
    private JsonArray rewriteVersionsArray(final String packageName, final JsonArray versions) {
        final JsonArrayBuilder arrayBuilder = Json.createArrayBuilder();

        for (final JsonValue versionValue : versions) {
            final JsonObject versionData = versionValue.asJsonObject();
            final String version = versionData.getString("version", "unknown");
            arrayBuilder.add(this.rewriteVersionData(packageName, version, versionData));
        }

        return arrayBuilder.build();
    }

    /**
     * Rewrite versions object for a package (v1 format).
     *
     * @param packageName Package name
     * @param versions Original versions object
     * @return Rewritten versions object
     */
    private JsonObject rewriteVersions(final String packageName, final JsonObject versions) {
        final JsonObjectBuilder versionsBuilder = Json.createObjectBuilder();

        for (final Map.Entry<String, JsonValue> versionEntry : versions.entrySet()) {
            final String version = versionEntry.getKey();
            final JsonObject versionData = versionEntry.getValue().asJsonObject();
            versionsBuilder.add(version, this.rewriteVersionData(packageName, version, versionData));
        }

        return versionsBuilder.build();
    }

    /**
     * Rewrite version data, particularly the dist.url field.
     * Also filters out special Packagist markers like "__unset" that should be removed.
     *
     * @param packageName Package name
     * @param version Version string
     * @param versionData Original version data
     * @return Rewritten version data
     */
    private JsonObject rewriteVersionData(
        final String packageName,
        final String version,
        final JsonObject versionData
    ) {
        final JsonObjectBuilder dataBuilder = Json.createObjectBuilder();

        for (final Map.Entry<String, JsonValue> entry : versionData.entrySet()) {
            final String key = entry.getKey();
            final JsonValue value = entry.getValue();
            
            // Skip fields with "__unset" marker (Packagist internal marker)
            if (value.getValueType() == JsonValue.ValueType.STRING) {
                final String strValue = ((javax.json.JsonString) value).getString();
                if ("__unset".equals(strValue)) {
                    // Skip this field entirely - it should not be in the output
                    continue;
                }
            }
            
            if ("dist".equals(key)) {
                dataBuilder.add(key, this.rewriteDist(packageName, version, value.asJsonObject()));
            } else {
                dataBuilder.add(key, value);
            }
        }

        return dataBuilder.build();
    }

    /**
     * Rewrite dist object to proxy the download through Pantera.
     *
     * @param packageName Package name
     * @param version Version string
     * @param dist Original dist object
     * @return Rewritten dist object
     */
    private JsonObject rewriteDist(
        final String packageName,
        final String version,
        final JsonObject dist
    ) {
        // Check if already rewritten (has original_url field)
        if (dist.containsKey("original_url")) {
            // Already rewritten, return as-is
            return dist;
        }

        final JsonObjectBuilder distBuilder = Json.createObjectBuilder();

        // Store original URL first (before copying other fields)
        final String originalUrl = dist.getString("url", null);
        
        // Copy all dist fields except url
        for (final Map.Entry<String, JsonValue> entry : dist.entrySet()) {
            final String key = entry.getKey();
            if (!"url".equals(key)) {
                distBuilder.add(key, entry.getValue());
            }
        }
        
        // Add original URL for ProxyDownloadSlice to use
        if (originalUrl != null) {
            distBuilder.add("original_url", originalUrl);
        }
        
        // Add rewritten proxy URL (with .zip extension for clarity)
        final String proxyUrl = String.format(
            "%s/dist/%s/%s.zip",
            this.baseUrl,
            packageName,
            version
        );
        distBuilder.add("url", proxyUrl);

        return distBuilder.build();
    }
}
