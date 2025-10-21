/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.composer.http.proxy;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Rewrites download URLs in Composer metadata to proxy through Artipie.
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
     * @param baseUrl Base URL for the Artipie repository (including repo path)
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
     * Rewrite dist object to proxy the download through Artipie.
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
        final JsonObjectBuilder distBuilder = Json.createObjectBuilder();

        // Copy all dist fields except url
        for (final Map.Entry<String, JsonValue> entry : dist.entrySet()) {
            final String key = entry.getKey();
            if ("url".equals(key)) {
                // Store original URL as reference  
                distBuilder.add("reference", entry.getValue());
                // Use full URL - Composer will use HTTP basic auth for same domain
                final String proxyUrl = String.format(
                    "%s/dist/%s/%s",
                    this.baseUrl,
                    packageName,
                    version
                );
                distBuilder.add("url", proxyUrl);
            } else {
                distBuilder.add(key, entry.getValue());
            }
        }

        return distBuilder.build();
    }
}
