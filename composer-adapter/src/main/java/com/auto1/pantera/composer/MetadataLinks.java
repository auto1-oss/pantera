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

import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonValue;

/**
 * Re-roots the links stored in a hosted Composer repository's metadata at the
 * base URL resolved for the current request.
 *
 * <p>Stored metadata carries whatever {@code dist.url} the writer froze in:
 * an absolute URL under the repository's {@code url:} at upload time (possibly
 * a host the repository is no longer reached by, e.g. after an import from
 * another registry), or a repository-relative path when no {@code url:} was
 * configured. A {@code dist.url} that points into this repository is rebuilt
 * as {@code <base>/<path inside the repository>}; any other URL (a dist hosted
 * elsewhere) is left untouched. Stored bytes are never modified.</p>
 *
 * @since 2.2.10
 */
public final class MetadataLinks {

    /**
     * Legacy alias of the archive path prefix; maps to the same storage key.
     */
    private static final String DIRECT_DISTS = "direct-dists/";

    /**
     * Path of an absolute URL served by this repository: an optional global
     * prefix segment, an optional {@code /api[/composer|/php]} route, the
     * repository segment, then the path inside the repository.
     */
    private final Pattern own;

    /**
     * Ctor.
     *
     * @param repo Repository name without surrounding slashes
     */
    public MetadataLinks(final String repo) {
        this.own = Pattern.compile(
            "^(?:/[^/]+)?(?:/api(?:/composer|/php)?)?/" + Pattern.quote(repo) + "/(.+)$"
        );
    }

    /**
     * Rewrite every {@code dist.url} of a per-package metadata document.
     *
     * @param stored Stored document
     * @param base Resolved base URL ending with a repository segment
     * @return Document with re-rooted dist URLs
     */
    public byte[] packages(final byte[] stored, final String base) {
        final JsonObject root = MetadataLinks.parse(stored);
        final JsonValue packages = root.get("packages");
        if (packages == null || packages.getValueType() != JsonValue.ValueType.OBJECT) {
            return stored;
        }
        final JsonObjectBuilder rewritten = Json.createObjectBuilder();
        for (final Map.Entry<String, JsonValue> pkg : packages.asJsonObject().entrySet()) {
            rewritten.add(pkg.getKey(), this.versions(pkg.getValue(), base));
        }
        return MetadataLinks.bytes(Json.createObjectBuilder(root).add("packages", rewritten).build());
    }

    /**
     * Rewrite the links of the root {@code packages.json}.
     *
     * @param stored Stored document
     * @param base Resolved base URL ending with a repository segment
     * @return Document whose metadata links point under {@code base}
     */
    public byte[] root(final byte[] stored, final String base) {
        final JsonObject root = MetadataLinks.parse(stored);
        final JsonObjectBuilder rewritten = Json.createObjectBuilder(root);
        rewritten.add("metadata-url", base + "/p2/%package%.json");
        if (root.containsKey("available-packages-url")) {
            rewritten.add("available-packages-url", base + "/p2/available-packages.json");
        }
        return MetadataLinks.bytes(rewritten.build());
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

    /**
     * Rewrite the versions of one package: an object keyed by version (v1) or
     * an array of versions (v2, possibly minified).
     *
     * @param versions Versions value
     * @param base Resolved base URL
     * @return Rewritten versions value
     */
    private JsonValue versions(final JsonValue versions, final String base) {
        final JsonValue result;
        if (versions.getValueType() == JsonValue.ValueType.ARRAY) {
            final JsonArrayBuilder array = Json.createArrayBuilder();
            for (final JsonValue version : versions.asJsonArray()) {
                array.add(this.version(version, base));
            }
            result = array.build();
        } else if (versions.getValueType() == JsonValue.ValueType.OBJECT) {
            final JsonObjectBuilder object = Json.createObjectBuilder();
            for (final Map.Entry<String, JsonValue> version : versions.asJsonObject().entrySet()) {
                object.add(version.getKey(), this.version(version.getValue(), base));
            }
            result = object.build();
        } else {
            result = versions;
        }
        return result;
    }

    /**
     * Rewrite the {@code dist.url} of one version, if it has one.
     *
     * @param version Version value
     * @param base Resolved base URL
     * @return Rewritten version value
     */
    private JsonValue version(final JsonValue version, final String base) {
        if (version.getValueType() != JsonValue.ValueType.OBJECT) {
            return version;
        }
        final JsonObject entry = version.asJsonObject();
        final JsonValue dist = entry.get("dist");
        if (dist == null || dist.getValueType() != JsonValue.ValueType.OBJECT
            || !(dist.asJsonObject().get("url") instanceof JsonString)) {
            return version;
        }
        final String url = dist.asJsonObject().getString("url");
        return Json.createObjectBuilder(entry)
            .add("dist", Json.createObjectBuilder(dist.asJsonObject()).add("url", this.dist(url, base)))
            .build();
    }

    /**
     * Parse a JSON object.
     *
     * @param bytes Bytes
     * @return JSON object
     */
    private static JsonObject parse(final byte[] bytes) {
        try (var reader = Json.createReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
            return reader.readObject();
        }
    }

    /**
     * Serialise a JSON object.
     *
     * @param json JSON object
     * @return UTF-8 bytes
     */
    private static byte[] bytes(final JsonObject json) {
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }
}
