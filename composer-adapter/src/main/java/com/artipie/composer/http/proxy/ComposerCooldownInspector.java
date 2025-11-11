/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.composer.http.proxy;

import com.artipie.asto.Content;
import com.artipie.cooldown.CooldownDependency;
import com.artipie.cooldown.CooldownInspector;
import com.artipie.http.Headers;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.jcabi.log.Logger;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.StringReader;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Composer cooldown inspector.
 * Fetches metadata from Packagist/Satis remotes to determine release dates and dependencies.
 *
 * @since 1.0
 */
public final class ComposerCooldownInspector implements CooldownInspector {

    /**
     * Remote slice for fetching metadata.
     */
    private final Slice remote;

    /**
     * Ctor.
     *
     * @param remote Remote slice
     */
    public ComposerCooldownInspector(final Slice remote) {
        this.remote = remote;
    }

    @Override
    public CompletableFuture<Optional<Instant>> releaseDate(
        final String artifact,
        final String version
    ) {
        Logger.info(this, "Checking release date for %s:%s", artifact, version);
        return this.fetchMetadata(artifact).thenApply(metadata -> {
            if (metadata.isEmpty()) {
                Logger.warn(this, "No metadata found for %s", artifact);
                return Optional.empty();
            }
            final JsonObject json = metadata.get();
            final JsonObject packages = json.getJsonObject("packages");
            if (packages == null) {
                Logger.warn(this, "No 'packages' object in metadata for %s", artifact);
                return Optional.empty();
            }
            final JsonObject versionData = findVersionData(packages, artifact, version);
            if (versionData == null) {
                Logger.warn(this, "Version %s not found for package %s", version, artifact);
                return Optional.empty();
            }
            final String timeStr = versionData.getString("time", null);
            if (timeStr != null) {
                try {
                    final java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(timeStr);
                    final Instant instant = odt.toInstant();
                    Logger.info(this, "Found release date for %s:%s = %s", artifact, version, instant);
                    return Optional.of(instant);
                } catch (final DateTimeParseException e) {
                    Logger.warn(this, "Failed to parse time '%s' for %s:%s", timeStr, artifact, version);
                }
            }
            Logger.warn(this, "No 'time' field found for %s:%s", artifact, version);
            return Optional.empty();
        });
    }

    @Override
    public CompletableFuture<List<CooldownDependency>> dependencies(
        final String artifact,
        final String version
    ) {
        Logger.info(this, "Fetching dependencies for %s:%s", artifact, version);
        return this.fetchMetadata(artifact).thenApply(metadata -> {
            if (metadata.isEmpty()) {
                Logger.warn(this, "No metadata found for %s", artifact);
                return Collections.emptyList();
            }
            final JsonObject json = metadata.get();
            final JsonObject packages = json.getJsonObject("packages");
            if (packages == null) {
                return Collections.emptyList();
            }
            final JsonObject versionData = findVersionData(packages, artifact, version);
            if (versionData == null) {
                return Collections.emptyList();
            }
            final JsonObject require = versionData.getJsonObject("require");
            if (require == null || require.isEmpty()) {
                Logger.debug(this, "No dependencies found for %s:%s", artifact, version);
                return Collections.emptyList();
            }
            final List<CooldownDependency> deps = new ArrayList<>();
            for (final String depName : require.keySet()) {
                if (depName.startsWith("php") || depName.startsWith("ext-")) {
                    continue;
                }
                final String versionConstraint = require.getString(depName);
                deps.add(new CooldownDependency(depName, versionConstraint));
            }
            Logger.info(this, "Found %d dependencies for %s:%s", deps.size(), artifact, version);
            return deps;
        });

    }

    private static JsonObject findVersionData(final JsonObject packages, final String artifact, final String version) {
        final javax.json.JsonValue pkgVal = packages.get(artifact);
        if (pkgVal == null) {
            return null;
        }
        final String normalized = stripV(version);
        if (pkgVal.getValueType() == javax.json.JsonValue.ValueType.ARRAY) {
            final javax.json.JsonArray arr = pkgVal.asJsonArray();
            for (javax.json.JsonValue v : arr) {
                final JsonObject vo = v.asJsonObject();
                final String vstr = stripV(vo.getString("version", ""));
                if (vstr.equals(normalized)) {
                    return vo;
                }
            }
            return null;
        }
        final JsonObject versions = pkgVal.asJsonObject();
        JsonObject data = versions.getJsonObject(version);
        if (data == null) {
            data = versions.getJsonObject(normalized);
        }
        return data;
    }

    private static String stripV(final String v) {
        if (v == null) {
            return "";
        }
        return v.startsWith("v") || v.startsWith("V") ? v.substring(1) : v;
    }

    /**
     * Fetch metadata for a package from the remote.
     *
     * @param packageName Package name (e.g., "vendor/package")
     * @return Future with optional JSON metadata
     */
    private CompletableFuture<Optional<JsonObject>> fetchMetadata(final String packageName) {
        // Packagist v2 API: /p2/{vendor}/{package}.json
        final String path = String.format("/p2/%s.json", packageName);
        Logger.debug(this, "Fetching metadata from %s", path);
        return this.remote.response(
            new RequestLine(RqMethod.GET, path),
            Headers.EMPTY,
            Content.EMPTY
        ).thenCompose(response -> {
            // CRITICAL: Always consume body to prevent Vert.x request leak
            return new Content.From(response.body()).asStringFuture().thenApply(content -> {
                if (!response.status().success()) {
                    Logger.warn(
                        this,
                        "Failed to fetch metadata for %s (status: %s)",
                        packageName,
                        response.status()
                    );
                    return Optional.empty();
                }
                try {
                    final JsonObject json = Json.createReader(new StringReader(content)).readObject();
                    return Optional.of(json);
                } catch (final Exception e) {
                    Logger.error(
                        this,
                        "Failed to parse JSON metadata for %s: %s",
                        packageName,
                        e.getMessage()
                    );
                    return Optional.empty();
                }
            });
        });
    }
}
