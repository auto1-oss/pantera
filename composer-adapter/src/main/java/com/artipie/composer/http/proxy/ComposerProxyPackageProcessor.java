/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.composer.http.proxy;

import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.scheduling.ProxyArtifactEvent;
import com.artipie.scheduling.QuartzJob;
import com.jcabi.log.Logger;

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
        if (this.asto == null || this.packages == null || this.events == null) {
            Logger.warn(this, "Composer proxy processor not initialized properly - stopping job");
            super.stopJob(context);
        } else {
            Logger.debug(this, "Composer proxy processor running, queue size: %d", this.packages.size());
            while (!this.packages.isEmpty()) {
                final ProxyArtifactEvent event = this.packages.poll();
                if (event != null) {
                    final Key key = event.artifactKey();
                    Logger.debug(this, "Processing Composer proxy event for key: %s", key.string());
                    try {
                        // Key format is now "vendor/package/version" from ProxyDownloadSlice
                        // Extract package name and version from key
                        final String[] parts = key.string().split("/");
                        if (parts.length < 3) {
                            Logger.warn(
                                this,
                                "Invalid event key format (expected vendor/package/version): %s",
                                key.string()
                            );
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
                                0L,  // Size unknown from download event
                                created,
                                release
                            )
                        );

                        Logger.info(
                            this,
                            "Recorded Composer proxy download %s:%s (repo=%s, owner=%s, release=%s)",
                            normalizedName,
                            version,
                            event.repoName(),
                            owner,
                            release == null ? "unknown" : java.time.Instant.ofEpochMilli(release).toString()
                        );

                        // Remove all duplicate events from queue
                        while (this.packages.remove(event)) {
                            // Continue removing duplicates
                        }

                    } catch (final Exception err) {
                        Logger.error(
                            this,
                            "Failed to process composer proxy package %s: %s",
                            key.string(),
                            err.getMessage()
                        );
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
            final com.artipie.asto.Key metadataKey = new com.artipie.asto.Key.From(packageName + ".json");
            
            if (!this.asto.exists(metadataKey).join()) {
                Logger.debug(this, "Metadata not found for %s, cannot extract release date", packageName);
                return null;
            }

            // Read and parse metadata
            final com.artipie.asto.Content content = this.asto.value(metadataKey).join();
            final String jsonStr = new String(
                new com.artipie.asto.Content.From(content).asBytesFuture().join(),
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
                Logger.debug(
                    this,
                    "Extracted release date for %s:%s = %s (%d)",
                    packageName,
                    version,
                    timeStr,
                    releaseMillis
                );
                return releaseMillis;
            }

            return null;
        } catch (final Exception err) {
            Logger.warn(
                this,
                "Failed to extract release date for %s:%s: %s",
                packageName,
                version,
                err.getMessage()
            );
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
