/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.composer.http.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.cooldown.CooldownDependency;
import com.auto1.pantera.cooldown.CooldownInspector;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;

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
        EcsLogger.debug("com.auto1.pantera.composer")
            .message("Checking release date for package")
            .eventCategory("repository")
            .eventAction("cooldown_release_date")
            .field("package.name", artifact)
            .field("package.version", version)
            .log();
        return this.fetchMetadata(artifact).thenApply(metadata -> {
            if (metadata.isEmpty()) {
                EcsLogger.warn("com.auto1.pantera.composer")
                    .message("No metadata found for package")
                    .eventCategory("repository")
                    .eventAction("cooldown_release_date")
                    .eventOutcome("failure")
                    .field("package.name", artifact)
                    .log();
                return Optional.empty();
            }
            final JsonObject json = metadata.get();
            final JsonObject packages = json.getJsonObject("packages");
            if (packages == null) {
                EcsLogger.warn("com.auto1.pantera.composer")
                    .message("No 'packages' object in metadata")
                    .eventCategory("repository")
                    .eventAction("cooldown_release_date")
                    .eventOutcome("failure")
                    .field("package.name", artifact)
                    .log();
                return Optional.empty();
            }
            final JsonObject versionData = findVersionData(packages, artifact, version);
            if (versionData == null) {
                EcsLogger.warn("com.auto1.pantera.composer")
                    .message("Version not found for package")
                    .eventCategory("repository")
                    .eventAction("cooldown_release_date")
                    .eventOutcome("failure")
                    .field("package.name", artifact)
                    .field("package.version", version)
                    .log();
                return Optional.empty();
            }
            final String timeStr = versionData.getString("time", null);
            if (timeStr != null) {
                try {
                    final java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(timeStr);
                    final Instant instant = odt.toInstant();
                    EcsLogger.debug("com.auto1.pantera.composer")
                        .message("Found release date for package")
                        .eventCategory("repository")
                        .eventAction("cooldown_release_date")
                        .eventOutcome("success")
                        .field("package.name", artifact)
                        .field("package.version", version)
                        .field("package.release_date", instant.toString())
                        .log();
                    return Optional.of(instant);
                } catch (final DateTimeParseException e) {
                    EcsLogger.warn("com.auto1.pantera.composer")
                        .message("Failed to parse time field: " + timeStr)
                        .eventCategory("repository")
                        .eventAction("cooldown_release_date")
                        .eventOutcome("failure")
                        .field("package.name", artifact)
                        .field("package.version", version)
                        .log();
                }
            }
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("No 'time' field found in metadata")
                .eventCategory("repository")
                .eventAction("cooldown_release_date")
                .eventOutcome("failure")
                .field("package.name", artifact)
                .field("package.version", version)
                .log();
            return Optional.empty();
        });
    }

    @Override
    public CompletableFuture<List<CooldownDependency>> dependencies(
        final String artifact,
        final String version
    ) {
        EcsLogger.debug("com.auto1.pantera.composer")
            .message("Fetching dependencies for package")
            .eventCategory("repository")
            .eventAction("cooldown_dependencies")
            .field("package.name", artifact)
            .field("package.version", version)
            .log();
        return this.fetchMetadata(artifact).thenApply(metadata -> {
            if (metadata.isEmpty()) {
                EcsLogger.warn("com.auto1.pantera.composer")
                    .message("No metadata found for package")
                    .eventCategory("repository")
                    .eventAction("cooldown_dependencies")
                    .eventOutcome("failure")
                    .field("package.name", artifact)
                    .log();
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
                EcsLogger.debug("com.auto1.pantera.composer")
                    .message("No dependencies found for package")
                    .eventCategory("repository")
                    .eventAction("cooldown_dependencies")
                    .field("package.name", artifact)
                    .field("package.version", version)
                    .log();
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
            EcsLogger.debug("com.auto1.pantera.composer")
                .message("Found " + deps.size() + " dependencies for package")
                .eventCategory("repository")
                .eventAction("cooldown_dependencies")
                .eventOutcome("success")
                .field("package.name", artifact)
                .field("package.version", version)
                .log();
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
        EcsLogger.debug("com.auto1.pantera.composer")
            .message("Fetching metadata from remote")
            .eventCategory("repository")
            .eventAction("metadata_fetch")
            .field("url.path", path)
            .field("package.name", packageName)
            .log();
        return this.remote.response(
            new RequestLine(RqMethod.GET, path),
            Headers.EMPTY,
            Content.EMPTY
        ).thenCompose(response -> {
            // CRITICAL: Always consume body to prevent Vert.x request leak
            return new Content.From(response.body()).asStringFuture().thenApply(content -> {
                if (!response.status().success()) {
                    EcsLogger.warn("com.auto1.pantera.composer")
                        .message("Failed to fetch metadata from remote")
                        .eventCategory("repository")
                        .eventAction("metadata_fetch")
                        .eventOutcome("failure")
                        .field("package.name", packageName)
                        .field("http.response.status_code", response.status().code())
                        .log();
                    return Optional.empty();
                }
                try {
                    final JsonObject json = Json.createReader(new StringReader(content)).readObject();
                    return Optional.of(json);
                } catch (final Exception e) {
                    EcsLogger.error("com.auto1.pantera.composer")
                        .message("Failed to parse JSON metadata")
                        .eventCategory("repository")
                        .eventAction("metadata_fetch")
                        .eventOutcome("failure")
                        .field("package.name", packageName)
                        .error(e)
                        .log();
                    return Optional.empty();
                }
            });
        });
    }
}
