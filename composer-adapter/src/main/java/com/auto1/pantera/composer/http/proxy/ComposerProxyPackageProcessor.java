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
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.CognitiveComplexity"})
    public void execute(final JobExecutionContext context) {
        this.resolveFromRegistry(context);
        if (this.asto == null || this.packages == null || this.events == null) {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Composer proxy processor not initialized properly - stopping job")
                .eventCategory("web")
                .eventAction("proxy_processor")
                .eventOutcome("failure")
                .log();
            super.stopJob(context);
        } else {
            EcsLogger.debug("com.auto1.pantera.composer")
                .message("Composer proxy processor running (queue size: " + this.packages.size() + ")")
                .eventCategory("web")
                .eventAction("proxy_processor")
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
                                .log();
                            continue;
                        }

                        final String vendor = parts[0];
                        final String pkg = parts[1];
                        final String version = parts[2];
                        final String packageName = vendor + "/" + pkg;
                        final String normalizedName = normalizePackageName(packageName);

                        final String owner = event.ownerLogin();
                        final long created = System.currentTimeMillis();

                        // Extract release date from cached metadata
                        final Long release = this.extractReleaseDate(packageName, version);

                        // Read size from storage (like Maven/npm adapters do)
                        long artifactSize = 0L;
                        try {
                            final Key distKey = new Key.From(
                                "dist", vendor, pkg,
                                pkg + "-" + version + ".zip"
                            );
                            if (this.asto.exists(distKey).join()) {
                                final var sizeOpt = this.asto.metadata(distKey)
                                    .join()
                                    .read(com.auto1.pantera.asto.Meta.OP_SIZE);
                            if (sizeOpt.isPresent()) {
                                artifactSize = sizeOpt.get();
                            }
                            }
                        } catch (final Exception ignored) {
                            // Fall back to 0 if size cannot be read
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
                            )
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
                            .log();
                    }
                }
            }
        }
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
    @SuppressWarnings("PMD.MethodNamingConventions")
    public void setEvents_key(final String key) {
        this.events = JobDataRegistry.lookup(key);
    }

    /**
     * Set registry key for packages queue (JDBC mode).
     * @param key Registry key
     */
    @SuppressWarnings("PMD.MethodNamingConventions")
    public void setPackages_key(final String key) {
        this.packages = JobDataRegistry.lookup(key);
    }

    /**
     * Set registry key for storage (JDBC mode).
     * @param key Registry key
     */
    @SuppressWarnings("PMD.MethodNamingConventions")
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
                .field("error.message", err.getMessage())
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
