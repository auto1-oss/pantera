/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven;

import com.artipie.asto.Key;
import com.artipie.asto.Meta;
import com.artipie.asto.Storage;
import com.artipie.asto.ext.KeyLastPart;
import com.artipie.maven.http.MavenSlice;
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.scheduling.ProxyArtifactEvent;
import com.artipie.scheduling.QuartzJob;
import com.jcabi.log.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.quartz.JobExecutionContext;

/**
 * Processes artifacts uploaded by proxy and adds info to artifacts metadata events queue.
 * @since 0.10
 */
public final class MavenProxyPackageProcessor extends QuartzJob {

    /**
     * Repository type.
     */
    private static final String REPO_TYPE = "maven-proxy";

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
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.EmptyControlStatement"})
    public void execute(final JobExecutionContext context) {
        if (this.asto == null || this.packages == null || this.events == null) {
            super.stopJob(context);
        } else {
            this.processPackagesBatch();
        }
    }

    /**
     * Process packages in parallel batches for better performance.
     */
    @SuppressWarnings({"PMD.AssignmentInOperand", "PMD.AvoidCatchingGenericException"})
    private void processPackagesBatch() {
        // Drain up to 100 packages for batch processing
        final List<ProxyArtifactEvent> batch = new ArrayList<>(100);
        ProxyArtifactEvent event = this.packages.poll();
        while (batch.size() < 100 && event != null) {
            batch.add(event);
            event = this.packages.poll();
        }

        if (batch.isEmpty()) {
            return;
        }

        // Deduplicate by artifact key - only process unique packages
        final List<ProxyArtifactEvent> uniquePackages = batch.stream()
            .collect(Collectors.toMap(
                e -> e.artifactKey().string(),  // Key: artifact path
                e -> e,                          // Value: first event
                (existing, duplicate) -> existing // Keep first, ignore duplicates
            ))
            .values()
            .stream()
            .collect(Collectors.toList());

        Logger.debug(
            this,
            "Processing Maven batch of %d packages (%d unique, %d duplicates removed)",
            batch.size(), uniquePackages.size(), batch.size() - uniquePackages.size()
        );

        // Process all unique packages in parallel
        List<CompletableFuture<Void>> futures = uniquePackages.stream()
            .map(this::processPackageAsync)
            .collect(Collectors.toList());

        // Wait for batch completion with timeout
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(30, TimeUnit.SECONDS)
                .join();
            Logger.debug(this, "Maven batch processing complete");
        } catch (final RuntimeException err) {
            Logger.error(this, "Maven batch processing failed: %s", err.getMessage());
        }
    }

    /**
     * Process a single package asynchronously.
     * @param event Package event to process
     * @return CompletableFuture that completes when processing is done
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private CompletableFuture<Void> processPackageAsync(final ProxyArtifactEvent event) {
        return this.asto.list(event.artifactKey())
            .thenCompose(keys -> {
                try {
                    // Filter out temporary files created during atomic saves
                    // Temp files have pattern: {filename}.{UUID}.tmp
                    final List<Key> filtered = keys.stream()
                        .filter(key -> !key.string().endsWith(".tmp"))
                        .collect(Collectors.toList());
                    
                    if (filtered.isEmpty()) {
                        Logger.debug(
                            this,
                            "Maven package %s has only temporary files, skipping (will retry later)",
                            event.artifactKey()
                        );
                        return CompletableFuture.completedFuture(null);
                    }
                    
                    final Key archive = MavenSlice.EVENT_INFO.artifactPackage(filtered);
                    return this.asto.metadata(archive)
                        .thenApply(meta -> meta.read(Meta.OP_SIZE).get())
                        .thenAccept(size -> {
                            final String owner = event.ownerLogin();
                            final long created = System.currentTimeMillis();
                            final Long release = event.releaseMillis().orElse(null);
                            final String artifactName = MavenSlice.EVENT_INFO.formatArtifactName(
                                event.artifactKey().parent().get()
                            );
                            final String version = new KeyLastPart(event.artifactKey()).get();
                            
                            this.events.add(
                                new ArtifactEvent(
                                    MavenProxyPackageProcessor.REPO_TYPE,
                                    event.repoName(),
                                    owner == null || owner.isBlank()
                                        ? ArtifactEvent.DEF_OWNER
                                        : owner,
                                    artifactName,
                                    version,
                                    size,
                                    created,
                                    release
                                )
                            );
                            
                            Logger.debug(
                                this,
                                "Recorded Maven proxy artifact %s:%s (size=%d)",
                                artifactName, version, size
                            );
                        })
                        .exceptionally(err -> {
                            // If ValueNotFoundException, the file might still be in transit
                            // This can happen if file was just moved after listing
                            if (err.getCause() instanceof com.artipie.asto.ValueNotFoundException) {
                                Logger.debug(
                                    this,
                                    "Maven package %s not found (likely still being written), will retry: %s",
                                    event.artifactKey(), err.getMessage()
                                );
                                // Re-queue event for retry on next batch
                                this.packages.add(event);
                            } else {
                                Logger.error(
                                    this,
                                    "Failed to read Maven artifact metadata %s: %s",
                                    event.artifactKey(), err.getMessage()
                                );
                            }
                            return null;
                        });
                } catch (final RuntimeException err) {
                    Logger.error(
                        this,
                        "Failed to extract Maven archive from keys: %s",
                        err.getMessage()
                    );
                    return CompletableFuture.completedFuture(null);
                }
            })
            .exceptionally(err -> {
                Logger.error(
                    this,
                    "Failed to process Maven package %s: %s",
                    event.artifactKey(), err.getMessage()
                );
                return null;
            });
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
}
