/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.goproxy;

import com.artipie.asto.Key;
import com.artipie.asto.Meta;
import com.artipie.asto.Storage;
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.scheduling.ProxyArtifactEvent;
import com.artipie.scheduling.QuartzJob;
import com.jcabi.log.Logger;
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
    @SuppressWarnings({"PMD.AvoidCatchingGenericException"})
    public void execute(final JobExecutionContext context) {
        if (this.asto == null || this.packages == null || this.events == null) {
            Logger.error(this, "Go proxy processor not initialized properly");
            super.stopJob(context);
        } else {
            Logger.info(this, "Go proxy processor running, packages queue size: %d", 
                this.packages.size());
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

        Logger.info(this, "Processing Go batch of %d packages", batch.size());

        List<CompletableFuture<Void>> futures = batch.stream()
            .map(this::processGoPackageAsync)
            .collect(Collectors.toList());

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(30, TimeUnit.SECONDS)
                .join();
            Logger.info(this, "Go batch processing complete");
        } catch (Exception err) {
            Logger.error(this, "Go batch processing failed: %s", err.getMessage());
        }
    }

    /**
     * Process a single Go package asynchronously.
     * @param event Package event
     * @return CompletableFuture
     */
    private CompletableFuture<Void> processGoPackageAsync(final ProxyArtifactEvent event) {
        final Key key = event.artifactKey();
        Logger.debug(this, "Processing Go proxy event for key: %s", key.string());

        // Parse module coordinates from event key
        final ModuleCoordinates coords = parseCoordinates(key);
        if (coords == null) {
            Logger.warn(this, "Could not parse coordinates from %s, skipping", key.string());
            return CompletableFuture.completedFuture(null);
        }

        // Build the .zip file key (with 'v' prefix in filename)
        final Key zipKey = new Key.From(
            String.format("%s/@v/v%s.zip", coords.module(), coords.version())
        );

        // Check existence and get metadata asynchronously
        return this.asto.exists(zipKey).thenCompose(exists -> {
            if (!exists) {
                Logger.warn(
                    this,
                    "No .zip file found for %s (expected: %s), re-queuing for retry",
                    key.string(),
                    zipKey.string()
                );
                // Re-add event to queue for retry
                this.packages.add(event);
                return CompletableFuture.completedFuture(null);
            }

            return this.asto.metadata(zipKey)
                .thenApply(meta -> meta.read(Meta.OP_SIZE))
                .thenApply(sizeOpt -> sizeOpt.map(Long::longValue))
                .thenAccept(size -> {
                    if (size.isEmpty()) {
                        Logger.warn(
                            this,
                            "Missing size metadata for %s, skipping",
                            zipKey.string()
                        );
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
                            release
                        )
                    );

                    Logger.info(
                        this,
                        "Recorded Go proxy module %s@v%s (repo=%s, size=%d, release=%s)",
                        coords.module(),
                        coords.version(),
                        event.repoName(),
                        size.get(),
                        release == null ? "unknown" 
                            : java.time.Instant.ofEpochMilli(release).toString()
                    );
                });
        }).exceptionally(err -> {
            Logger.error(
                this,
                "Failed to process Go package %s: %s",
                key.string(),
                err.getMessage()
            );
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
