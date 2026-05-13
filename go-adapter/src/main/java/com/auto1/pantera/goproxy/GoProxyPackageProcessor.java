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
package com.auto1.pantera.goproxy;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.quartz.JobExecutionContext;

/**
 * Processes artifacts uploaded by Go proxy and adds info to artifacts metadata events queue.
 * Go modules use the format: module/path/@v/version.{info|mod|zip}
 * 
 * @since 1.0
 */
public final class GoProxyPackageProcessor extends QuartzJob {

    /**
     * Repository type.
     */
    private static final String REPO_TYPE = "go-proxy";

    /**
     * Pattern to match Go module event keys.
     * Matches: module/path/@v/version (without 'v' prefix or file extension)
     * Example: github.com/google/uuid/@v/1.3.0
     */
    private static final Pattern ARTIFACT_PATTERN = 
        Pattern.compile("^/?(?<module>.+)/@v/(?<version>[^/]+)$");

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
            EcsLogger.error("com.auto1.pantera.go")
                .message("Go proxy processor not initialized properly")
                .eventCategory("web")
                .eventAction("proxy_processor")
                .eventOutcome("failure")
                .log();
            super.stopJob(context);
        } else {
            EcsLogger.debug("com.auto1.pantera.go")
                .message("Go proxy processor running (queue size: " + this.packages.size() + ")")
                .eventCategory("web")
                .eventAction("proxy_processor")
                .log();
            this.processPackagesBatch();
        }
    }

    /**
     * Process packages in parallel batches.
     */
    private void processPackagesBatch() {
        final List<ProxyArtifactEvent> batch = new ArrayList<>(100);
        ProxyArtifactEvent event;
        while (batch.size() < 100 && (event = this.packages.poll()) != null) {
            batch.add(event);
        }

        if (batch.isEmpty()) {
            return;
        }

        EcsLogger.info("com.auto1.pantera.go")
            .message("Processing Go batch (size: " + batch.size() + ")")
            .eventCategory("web")
            .eventAction("proxy_processor")
            .log();

        List<CompletableFuture<Void>> futures = batch.stream()
            .map(this::processGoPackageAsync)
            .collect(Collectors.toList());

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(30, TimeUnit.SECONDS)
                .join();
            EcsLogger.info("com.auto1.pantera.go")
                .message("Go batch processing complete (size: " + batch.size() + ")")
                .eventCategory("web")
                .eventAction("proxy_processor")
                .eventOutcome("success")
                .log();
        } catch (Exception err) {
            EcsLogger.error("com.auto1.pantera.go")
                .message("Go batch processing failed (size: " + batch.size() + ")")
                .eventCategory("web")
                .eventAction("proxy_processor")
                .eventOutcome("failure")
                .error(err)
                .log();
        }
    }

    /**
     * Process a single Go package asynchronously.
     * @param event Package event
     * @return CompletableFuture
     */
    private CompletableFuture<Void> processGoPackageAsync(final ProxyArtifactEvent event) {
        final Key key = event.artifactKey();
        EcsLogger.debug("com.auto1.pantera.go")
            .message("Processing Go proxy event")
            .eventCategory("web")
            .eventAction("proxy_processor")
            .field("package.name", key.string())
            .log();

        // Parse module coordinates from event key
        final ModuleCoordinates coords = parseCoordinates(key);
        if (coords == null) {
            EcsLogger.warn("com.auto1.pantera.go")
                .message("Could not parse coordinates, skipping")
                .eventCategory("web")
                .eventAction("proxy_processor")
                .eventOutcome("failure")
                .field("package.name", key.string())
                .log();
            return CompletableFuture.completedFuture(null);
        }

        // Build the .zip file key (with 'v' prefix in filename)
        final Key zipKey = new Key.From(
            String.format("%s/@v/v%s.zip", coords.module(), coords.version())
        );

        // Check existence and get metadata asynchronously
        return this.asto.exists(zipKey).thenCompose(exists -> {
            if (!exists) {
                EcsLogger.warn("com.auto1.pantera.go")
                    .message("No .zip file found, re-queuing for retry")
                    .eventCategory("web")
                    .eventAction("proxy_processor")
                    .eventOutcome("failure")
                    .field("package.name", key.string())
                    .field("file.target_path", zipKey.string())
                    .log();
                // Re-add event to queue for retry
                this.packages.add(event);
                return CompletableFuture.completedFuture(null);
            }

            return this.asto.metadata(zipKey)
                .thenApply(meta -> meta.read(Meta.OP_SIZE))
                .thenApply(sizeOpt -> sizeOpt.map(Long::longValue))
                .thenAccept(size -> {
                    if (size.isEmpty()) {
                        EcsLogger.warn("com.auto1.pantera.go")
                            .message("Missing size metadata, skipping")
                            .eventCategory("web")
                            .eventAction("proxy_processor")
                            .eventOutcome("failure")
                            .field("file.path", zipKey.string())
                            .log();
                        return;
                    }

                    final String owner = event.ownerLogin();
                    final long created = System.currentTimeMillis();
                    final Long release = event.releaseMillis().orElse(null);

                    this.events.add(
                        new ArtifactEvent(
                            GoProxyPackageProcessor.REPO_TYPE,
                            event.repoName(),
                            owner == null || owner.isBlank()
                                ? ArtifactEvent.DEF_OWNER
                                : owner,
                            coords.module(),
                            coords.version(),
                            size.get(),
                            created,
                            release,
                            event.artifactKey().string()
                        )
                    );

                    EcsLogger.info("com.auto1.pantera.go")
                        .message("Recorded Go proxy module")
                        .eventCategory("web")
                        .eventAction("proxy_processor")
                        .eventOutcome("success")
                        .field("package.name", coords.module())
                        .field("package.version", coords.version())
                        .field("repository.name", event.repoName())
                        .field("package.size", size.get())
                        .field("package.release_date", release == null ? null
                            : java.time.Instant.ofEpochMilli(release).toString())
                        .log();
                });
        }).exceptionally(err -> {
            EcsLogger.error("com.auto1.pantera.go")
                .message("Failed to process Go package")
                .eventCategory("web")
                .eventAction("proxy_processor")
                .eventOutcome("failure")
                .field("package.name", key.string())
                .error(err)
                .log();
            return null;
        });
    }


    /**
     * Parse module coordinates from Go module event key.
     * Expected format: module/path/@v/version (without 'v' prefix)
     * Example: github.com/google/uuid/@v/1.3.0
     * 
     * @param key Artifact key
     * @return Module coordinates or null if parsing fails
     */
    private static ModuleCoordinates parseCoordinates(final Key key) {
        final String path = key.string();
        final Matcher matcher = ARTIFACT_PATTERN.matcher(path);
        
        if (!matcher.matches()) {
            return null;
        }

        final String module = matcher.group("module");
        final String version = matcher.group("version");
        
        return new ModuleCoordinates(module, version);
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
     * Module coordinates holder.
     */
    private static final class ModuleCoordinates {
        private final String module;
        private final String version;

        ModuleCoordinates(final String module, final String version) {
            this.module = module;
            this.version = version;
        }

        String module() {
            return this.module;
        }

        String version() {
            return this.version;
        }
    }
}
