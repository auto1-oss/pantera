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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.scheduling.JobDataRegistry;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import com.auto1.pantera.scheduling.QuartzJob;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import org.quartz.JobExecutionContext;

/**
 * Processes Composer packages downloaded by proxy and adds info to artifacts metadata events queue.
 * Parses package metadata JSON to extract version info and emits database events.
 *
 * @since 1.0
 */
public final class ComposerProxyPackageProcessor extends QuartzJob {

    /**
     * Repository type.
     */
    private static final String REPO_TYPE = "php-proxy";

    /**
     * Artifact events queue.
     */
    private Queue<ArtifactEvent> events;

    /**
     * Queue with packages and owner names.
     */
    private Queue<ProxyArtifactEvent> packages;

    /**
     * Repository storage.
     */
    private Storage asto;

    @Override
    public void execute(final JobExecutionContext context) {
        this.resolveFromRegistry(context);
        if (this.asto == null || this.packages == null || this.events == null) {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Composer proxy processor not initialized properly - stopping job")
                .eventCategory("web")
                .eventAction("proxy_processor")
                .eventOutcome("failure")
                .field("log.source", "application")
                .log();
            super.stopJob(context);
        } else {
            EcsLogger.debug("com.auto1.pantera.composer")
                .message("Composer proxy processor running (queue size: " + this.packages.size() + ")")
                .eventCategory("web")
                .eventAction("proxy_processor")
                .field("log.source", "application")
                .log();
            while (!this.packages.isEmpty()) {
                final ProxyArtifactEvent event = this.packages.poll();
                if (event != null) {
                    final Key key = event.artifactKey();
                    EcsLogger.debug("com.auto1.pantera.composer")
                        .message("Processing Composer proxy event")
                        .eventCategory("web")
                        .eventAction("proxy_processor")
                        .field("package.path", key.string())
                        .field("log.source", "application")
                        .log();
                    try {
                        // Key format is now "vendor/package/version" from ProxyDownloadSlice
                        // Extract package name and version from key
                        final String[] parts = key.string().split("/");
                        if (parts.length < 3) {
                            EcsLogger.warn("com.auto1.pantera.composer")
                                .message("Invalid event key format (expected vendor/package/version)")
                                .eventCategory("web")
                                .eventAction("proxy_processor")
                                .eventOutcome("failure")
                                .field("package.path", key.string())
                                .field("log.source", "application")
                                .log();
                            continue;
                        }

                        final String vendor = parts[0];
                        final String pkg = parts[1];
                        // Branch versions may contain '/' (dev-feature/x).
                        final String version = String.join(
                            "/", java.util.Arrays.copyOfRange(parts, 2, parts.length)
                        );
                        final String packageName = vendor + "/" + pkg;
                        final String normalizedName = normalizePackageName(packageName);

                        final String owner = event.ownerLogin();
                        final long created = System.currentTimeMillis();

                        // Extract release date from cached metadata
                        final Long release = this.extractReleaseDate(packageName, version);

                        // Read size from storage (like Maven/npm adapters do)
                        long artifactSize = 0L;
                        try {
                            final Optional<Key> distKey = this.distKey(
                                vendor, pkg, packageName, version
                            );
                            if (distKey.isPresent()) {
                                final var sizeOpt = this.asto.metadata(distKey.get())
                                    .join()
                                    .read(com.auto1.pantera.asto.Meta.OP_SIZE);
                                if (sizeOpt.isPresent()) {
                                    artifactSize = sizeOpt.get();
                                }
                            }
                        } catch (final Exception ignored) {
                            // EXPECTED: size is a best-effort hint for the
                            // artifact event; zero is a valid fallback and
                            // the import will continue with the rest of
                            // the metadata.
                        }

                        // Record only the specific version that was downloaded
                        this.events.add(
                            new ArtifactEvent(
                                ComposerProxyPackageProcessor.REPO_TYPE,
                                event.repoName(),
                                owner == null || owner.isBlank()
                                    ? ArtifactEvent.DEF_OWNER
                                    : owner,
                                normalizedName,
                                version,
                                artifactSize,
                                created,
                                release,
                                event.artifactKey().string()
                            ).withContext(event.traceId(), event.clientIp())
                        );

                        EcsLogger.info("com.auto1.pantera.composer")
                            .message("Recorded Composer proxy download")
                            .eventCategory("web")
                            .eventAction("proxy_processor")
                            .eventOutcome("success")
                            .field("package.name", normalizedName)
                            .field("package.version", version)
                            .field("repository.name", event.repoName())
                            .field("user.name", owner)
                            .field("package.release_date", release == null ? null : java.time.Instant.ofEpochMilli(release).toString())
                            .field("log.source", "application")
                            .log();

                        // Remove all duplicate events from queue
                        while (this.packages.remove(event)) {
                            // Continue removing duplicates
                        }

                    } catch (final Exception err) {
                        EcsLogger.error("com.auto1.pantera.composer")
                            .message("Failed to process composer proxy package")
                            .eventCategory("web")
                            .eventAction("proxy_processor")
                            .eventOutcome("failure")
                            .field("package.path", key.string())
                            .error(err)
                            .field("log.source", "application")
                            .log();
                    }
                }
            }
        }
    }

    /**
     * Storage key of the cached dist of a downloaded version, as written by
     * {@link ProxyDownloadSlice}: {@code dist/<vendor>/<pkg>/<version>.zip},
     * the legacy key without {@code .zip}, or — for a dev branch — the key of
     * the reference the cached metadata currently names.
     *
     * @param vendor Vendor
     * @param pkg Package
     * @param packageName Package name ({@code vendor/pkg})
     * @param version Version
     * @return Existing dist key, if any
     */
    private Optional<Key> distKey(
        final String vendor, final String pkg, final String packageName, final String version
    ) {
        final List<Key> candidates = new ArrayList<>(3);
        candidates.add(new Key.From("dist", vendor, pkg, version + ".zip"));
        candidates.add(new Key.From("dist", vendor, pkg, version));
        final DevDistReference refs = new DevDistReference();
        if (refs.mutable(version)) {
            this.currentReference(packageName, version)
                .ifPresent(ref -> candidates.add(0, refs.key(vendor, pkg, version, ref)));
        }
        for (final Key candidate : candidates) {
            if (this.asto.exists(candidate).join()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * The {@code dist.reference} the cached metadata names for a version
     * (dev file first, then the stable file; object or array layout).
     *
     * @param packageName Package name
     * @param version Version
     * @return Reference, if found
     */
    private Optional<String> currentReference(final String packageName, final String version) {
        for (final String file : List.of(packageName + "~dev.json", packageName + ".json")) {
            final Key key = new Key.From(file);
            if (!this.asto.exists(key).join()) {
                continue;
            }
            final javax.json.JsonObject meta = javax.json.Json.createReader(
                new java.io.StringReader(new String(
                    this.asto.value(key).join().asBytesFuture().join(),
                    java.nio.charset.StandardCharsets.UTF_8
                ))
            ).readObject();
            final Optional<String> ref = ComposerProxyPackageProcessor.referenceIn(
                meta.getJsonObject("packages"), packageName, version
            );
            if (ref.isPresent()) {
                return ref;
            }
        }
        return Optional.empty();
    }

    /**
     * Find a version's {@code dist.reference} in a {@code packages} object.
     *
     * @param packages Packages object, may be null
     * @param packageName Package name
     * @param version Version
     * @return Reference, if present
     */
    private static Optional<String> referenceIn(
        final javax.json.JsonObject packages, final String packageName, final String version
    ) {
        if (packages == null || !packages.containsKey(packageName)) {
            return Optional.empty();
        }
        final javax.json.JsonValue pkg = packages.get(packageName);
        javax.json.JsonValue entry = null;
        if (pkg instanceof javax.json.JsonArray arr) {
            for (final javax.json.JsonValue item : arr) {
                if (item instanceof javax.json.JsonObject obj
                    && version.equals(obj.getString("version", ""))) {
                    entry = obj;
                    break;
                }
            }
        } else if (pkg instanceof javax.json.JsonObject obj) {
            entry = obj.get(version);
        }
        if (entry instanceof javax.json.JsonObject obj
            && obj.get("dist") instanceof javax.json.JsonObject dist
            && dist.get("reference") instanceof javax.json.JsonString ref) {
            return Optional.of(ref.getString());
        }
        return Optional.empty();
    }

    /**
     * Setter for events queue.
     * @param queue Events queue
     */
    public void setEvents(final Queue<ArtifactEvent> queue) {
        this.events = queue;
    }

    /**
     * Packages queue setter.
     * @param queue Queue with package key and owner
     */
    public void setPackages(final Queue<ProxyArtifactEvent> queue) {
        this.packages = queue;
    }

    /**
     * Repository storage setter.
     * @param storage Storage
     */
    public void setStorage(final Storage storage) {
        this.asto = storage;
    }

    /**
     * Set registry key for events queue (JDBC mode).
     * @param key Registry key
     */
    public void setEvents_key(final String key) {
        this.events = JobDataRegistry.lookup(key);
    }

    /**
     * Set registry key for packages queue (JDBC mode).
     * @param key Registry key
     */
    public void setPackages_key(final String key) {
        this.packages = JobDataRegistry.lookup(key);
    }

    /**
     * Set registry key for storage (JDBC mode).
     * @param key Registry key
     */
    public void setStorage_key(final String key) {
        this.asto = JobDataRegistry.lookup(key);
    }

    /**
     * Resolve fields from job data registry if registry keys are present
     * in the context and the fields are not yet set (JDBC mode fallback).
     * @param context Job execution context
     */
    private void resolveFromRegistry(final JobExecutionContext context) {
        if (context == null) {
            return;
        }
        final org.quartz.JobDataMap data = context.getMergedJobDataMap();
        if (this.packages == null && data.containsKey("packages_key")) {
            this.packages = JobDataRegistry.lookup(data.getString("packages_key"));
        }
        if (this.asto == null && data.containsKey("storage_key")) {
            this.asto = JobDataRegistry.lookup(data.getString("storage_key"));
        }
        if (this.events == null && data.containsKey("events_key")) {
            this.events = JobDataRegistry.lookup(data.getString("events_key"));
        }
    }

    /**
     * Extract release date from cached package metadata.
     * Reads the metadata JSON and extracts the 'time' field for the specific version.
     *
     * @param packageName Package name (vendor/package)
     * @param version Package version
     * @return Release timestamp in milliseconds, or null if not found
     */
    private Long extractReleaseDate(final String packageName, final String version) {
        try {
            // Metadata is stored at: vendor/package.json
            final com.auto1.pantera.asto.Key metadataKey = new com.auto1.pantera.asto.Key.From(packageName + ".json");

            if (!this.asto.exists(metadataKey).join()) {
                EcsLogger.debug("com.auto1.pantera.composer")
                    .message("Metadata not found, cannot extract release date")
                    .eventCategory("web")
                    .eventAction("proxy_processor")
                    .field("package.name", packageName)
                    .field("log.source", "application")
                    .log();
                return null;
            }

            // Read and parse metadata
            final com.auto1.pantera.asto.Content content = this.asto.value(metadataKey).join();
            final String jsonStr = new String(
                new com.auto1.pantera.asto.Content.From(content).asBytesFuture().join(),
                java.nio.charset.StandardCharsets.UTF_8
            );
            
            final javax.json.JsonObject metadata = javax.json.Json.createReader(
                new java.io.StringReader(jsonStr)
            ).readObject();

            // Navigate to packages[packageName][version].time
            final javax.json.JsonObject packages = metadata.getJsonObject("packages");
            if (packages == null) {
                return null;
            }

            final javax.json.JsonObject versions = packages.getJsonObject(packageName);
            if (versions == null) {
                return null;
            }

            final javax.json.JsonObject versionData = versions.getJsonObject(version);
            if (versionData == null) {
                return null;
            }

            // Extract release date from 'time' field (ISO 8601 format)
            final String timeStr = versionData.getString("time", null);
            if (timeStr != null) {
                final java.time.Instant instant = java.time.Instant.parse(timeStr);
                final long releaseMillis = instant.toEpochMilli();
                EcsLogger.debug("com.auto1.pantera.composer")
                    .message("Extracted release date from metadata")
                    .eventCategory("web")
                    .eventAction("proxy_processor")
                    .field("package.name", packageName)
                    .field("package.version", version)
                    .field("package.release_date", timeStr)
                    .field("log.source", "application")
                    .log();
                return releaseMillis;
            }

            return null;
        } catch (final Exception err) {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Failed to extract release date")
                .eventCategory("web")
                .eventAction("proxy_processor")
                .eventOutcome("failure")
                .field("package.name", packageName)
                .field("package.version", version)
                .error(err)
                .field("log.source", "application")
                .log();
            return null;
        }
    }

    /**
     * Normalize package name to handle both "vendor/package" and "package" formats.
     * If no vendor is present, uses "default" as vendor prefix.
     *
     * @param packageName Original package name
     * @return Normalized package name in "vendor/package" format
     */
    private static String normalizePackageName(final String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return packageName;
        }
        // If already has vendor prefix (contains /), return as-is
        if (packageName.contains("/")) {
            return packageName;
        }
        // If no vendor, add "default" prefix for consistency
        // This ensures database artifact names are always in vendor/package format
        return "default/" + packageName;
    }
}
