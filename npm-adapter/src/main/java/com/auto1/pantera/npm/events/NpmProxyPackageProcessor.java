/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.events;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.trace.TraceContext;
import com.auto1.pantera.npm.http.UploadSlice;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.scheduling.JobDataRegistry;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import com.auto1.pantera.scheduling.QuartzJob;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.quartz.JobExecutionContext;

/**
 * NPM proxy package processor - processes downloaded packages for event tracking.
 * <br/>
 * OPTIMIZED: Extracts package name/version from tarball PATH instead of reading
 * the tgz contents. This eliminates:
 * - Race conditions (reading incomplete files while being written)
 * - I/O overhead (no storage reads for validation)
 * - CPU overhead (no gzip decompression)
 * <br/>
 * NPM tarball paths follow convention: {name}/-/{name}-{version}.tgz
 * @since 1.5
 */
@SuppressWarnings("PMD.DataClass")
public final class NpmProxyPackageProcessor extends QuartzJob {

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

    /**
     * Pantera host (host only).
     */
    private String host;

    @Override
    @SuppressWarnings("PMD.CyclomaticComplexity")
    public void execute(final JobExecutionContext context) {
        this.resolveFromRegistry(context);
        if (this.asto == null || this.packages == null || this.host == null
            || this.events == null) {
            super.stopJob(context);
        } else {
            this.processPackagesBatch();
        }
    }

    /**
     * Process packages in parallel batches.
     */
    private void processPackagesBatch() {
        // Set trace context for background job
        final String traceId = TraceContext.generateTraceId();
        TraceContext.set(traceId);

        final List<ProxyArtifactEvent> batch = new ArrayList<>(100);
        ProxyArtifactEvent item;
        while (batch.size() < 100 && (item = this.packages.poll()) != null) {
            batch.add(item);
        }

        if (batch.isEmpty()) {
            return;
        }

        final long startTime = System.currentTimeMillis();

        EcsLogger.debug("com.auto1.pantera.npm")
            .message("Processing NPM batch (size: " + batch.size() + ")")
            .eventCategory("repository")
            .eventAction("batch_processing")
            .log();

        List<CompletableFuture<Void>> futures = batch.stream()
            .map(this::processPackageAsync)
            .collect(Collectors.toList());

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(30, TimeUnit.SECONDS)
                .join();

            final long duration = System.currentTimeMillis() - startTime;
            EcsLogger.info("com.auto1.pantera.npm")
                .message("NPM batch processing complete (size: " + batch.size() + ")")
                .eventCategory("repository")
                .eventAction("batch_processing")
                .eventOutcome("success")
                .duration(duration)
                .log();
        } catch (Exception err) {
            final long duration = System.currentTimeMillis() - startTime;
            EcsLogger.error("com.auto1.pantera.npm")
                .message("NPM batch processing failed (size: " + batch.size() + ")")
                .eventCategory("repository")
                .eventAction("batch_processing")
                .eventOutcome("failure")
                .duration(duration)
                .error(err)
                .log();
        } finally {
            TraceContext.clear();
        }
    }

    /**
     * Process a single package asynchronously.
     * OPTIMIZED: Extracts name/version from path instead of reading tgz contents.
     * This eliminates:
     * - Race conditions (reading incomplete files)
     * - I/O overhead (no storage reads for validation)
     * - CPU overhead (no gzip decompression)
     * @param item Package event
     * @return CompletableFuture
     */
    private CompletableFuture<Void> processPackageAsync(final ProxyArtifactEvent item) {
        // Parse name/version from path - ZERO I/O, ZERO race conditions
        final Optional<PackageCoords> coords = parsePackageCoords(item.artifactKey());
        if (coords.isEmpty()) {
            EcsLogger.warn("com.auto1.pantera.npm")
                .message("Could not parse package coords from path")
                .eventCategory("repository")
                .eventAction("package_validation")
                .field("package.path", item.artifactKey().string())
                .log();
            return CompletableFuture.completedFuture(null);
        }
        final String name = coords.get().name;
        final String version = coords.get().version;

        // Only I/O: get file size from metadata (fast, no content read)
        return this.asto.metadata(item.artifactKey())
            .thenApply(meta -> {
                // Meta.OP_SIZE returns Optional<? extends Long>, need to handle carefully
                final Optional<Long> sizeOpt = meta.read(Meta.OP_SIZE).map(Long::valueOf);
                return sizeOpt.orElse(0L);
            })
            .thenAccept(size -> {
                final long created = System.currentTimeMillis();
                final Long release = item.releaseMillis().orElse(null);
                this.events.add(
                    new ArtifactEvent(
                        UploadSlice.REPO_TYPE, item.repoName(), item.ownerLogin(),
                        name, version, size.longValue(), created, release,
                        item.artifactKey().string()
                    )
                );
                EcsLogger.debug("com.auto1.pantera.npm")
                    .message("Package event created from path")
                    .eventCategory("repository")
                    .eventAction("package_processing")
                    .field("package.name", name)
                    .field("package.version", version)
                    .log();
            })
            .exceptionally(err -> {
                EcsLogger.error("com.auto1.pantera.npm")
                    .message("Failed to process NPM package")
                    .eventCategory("repository")
                    .eventAction("package_processing")
                    .eventOutcome("failure")
                    .field("package.path", item.artifactKey().string())
                    .error(err)
                    .log();
                return null;
            });
    }

    /**
     * Parse package name and version from tarball path.
     * NPM tarball paths follow convention: {name}/-/{name}-{version}.tgz
     * Examples:
     * - lodash/-/lodash-4.17.21.tgz → (lodash, 4.17.21)
     * - @babel/core/-/@babel/core-7.23.0.tgz → (@babel/core, 7.23.0)
     * - @types/node/-/@types/node-20.10.0.tgz → (@types/node, 20.10.0)
     * @param key Storage key for tgz file
     * @return Optional package coordinates
     */
    private static Optional<PackageCoords> parsePackageCoords(final Key key) {
        final String path = key.string();
        // Find the /-/ separator that NPM uses
        final int sep = path.indexOf("/-/");
        if (sep < 0) {
            return Optional.empty();
        }
        final String name = path.substring(0, sep);
        final String filename = path.substring(sep + 3); // Skip "/-/"
        // Filename format: {name}-{version}.tgz
        // For scoped packages: @scope/pkg → @scope/pkg-1.0.0.tgz
        if (!filename.endsWith(".tgz")) {
            return Optional.empty();
        }
        final String withoutExt = filename.substring(0, filename.length() - 4);
        // Version is after the last hyphen that's preceded by a digit or after package name
        // Handle edge cases like: package-name-1.0.0-beta.1
        // Strategy: The version starts after "{name}-"
        final String expectedPrefix = name.contains("/")
            ? name.substring(name.lastIndexOf('/') + 1) + "-"
            : name + "-";
        if (!withoutExt.startsWith(expectedPrefix)) {
            // Fallback: find last hyphen followed by digit
            return parseVersionFallback(name, withoutExt);
        }
        final String version = withoutExt.substring(expectedPrefix.length());
        if (version.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PackageCoords(name, version));
    }

    /**
     * Fallback version parsing: find the version by looking for semver pattern.
     * @param name Package name
     * @param filename Filename without .tgz extension
     * @return Optional package coordinates
     */
    private static Optional<PackageCoords> parseVersionFallback(
        final String name, final String filename
    ) {
        // Find pattern: hyphen followed by digit (start of version)
        for (int i = filename.length() - 1; i > 0; i--) {
            if (filename.charAt(i - 1) == '-' && Character.isDigit(filename.charAt(i))) {
                final String version = filename.substring(i);
                return Optional.of(new PackageCoords(name, version));
            }
        }
        return Optional.empty();
    }

    /**
     * Simple holder for package name and version.
     */
    private static final class PackageCoords {
        /**
         * Package name.
         */
        final String name;
        /**
         * Package version.
         */
        final String version;

        PackageCoords(final String name, final String version) {
            this.name = name;
            this.version = version;
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
     * @param queue Queue with package tgz key and owner
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
     * Set repository host.
     * @param url The host
     */
    public void setHost(final String url) {
        this.host = url;
        if (this.host.endsWith("/")) {
            this.host = this.host.substring(0, this.host.length() - 2);
        }
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

}
