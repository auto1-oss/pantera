/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven;

import com.artipie.asto.Key;
import com.artipie.asto.Meta;
import com.artipie.asto.Storage;
import com.artipie.asto.ext.KeyLastPart;
import com.artipie.http.log.EcsLogger;
import com.artipie.http.trace.TraceContext;
import com.artipie.maven.http.MavenSlice;
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.scheduling.JobDataRegistry;
import com.artipie.scheduling.ProxyArtifactEvent;
import com.artipie.scheduling.QuartzJob;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
     * Maximum number of retry attempts for failed package processing.
     * After this many retries, the package will be dropped with a warning.
     *
     * @since 1.19.2
     */
    private static final int MAX_RETRIES = 3;

    /**
     * Retry count tracker for each artifact key.
     * Maps artifact key to number of retry attempts.
     * Used to prevent infinite retry loops for permanently failing packages.
     *
     * @since 1.19.2
     */
    private final ConcurrentHashMap<String, Integer> retryCount = new ConcurrentHashMap<>();

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
        this.resolveFromRegistry(context);
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
        // Set trace context for background job
        final String traceId = TraceContext.generateTraceId();
        TraceContext.set(traceId);

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

        final long startTime = System.currentTimeMillis();
        final int duplicatesRemoved = batch.size() - uniquePackages.size();

        EcsLogger.debug("com.artipie.maven")
            .message("Processing Maven batch (batch size: " + batch.size() + ", unique: " + uniquePackages.size() + ", duplicates removed: " + duplicatesRemoved + ")")
            .eventCategory("repository")
            .eventAction("batch_processing")
            .log();

        // Process all unique packages in parallel
        List<CompletableFuture<Void>> futures = uniquePackages.stream()
            .map(this::processPackageAsync)
            .collect(Collectors.toList());

        // Wait for batch completion with timeout
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(30, TimeUnit.SECONDS)
                .join();

            final long duration = System.currentTimeMillis() - startTime;
            EcsLogger.info("com.artipie.maven")
                .message("Maven batch processing complete (" + uniquePackages.size() + " packages)")
                .eventCategory("repository")
                .eventAction("batch_processing")
                .eventOutcome("success")
                .duration(duration)
                .log();
        } catch (final RuntimeException err) {
            final long duration = System.currentTimeMillis() - startTime;
            EcsLogger.error("com.artipie.maven")
                .message("Maven batch processing failed (" + uniquePackages.size() + " packages)")
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
                        EcsLogger.debug("com.artipie.maven")
                            .message("Maven package has only temporary files, skipping (will retry later)")
                            .eventCategory("repository")
                            .eventAction("proxy_package_process")
                            .eventOutcome("skipped")
                            .field("repository.type", REPO_TYPE)
                            .field("package.name", event.artifactKey().string())
                            .log();
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
                                    release,
                                    event.artifactKey().string()
                                )
                            );

                            // Clear retry count on successful processing
                            this.retryCount.remove(event.artifactKey().string());

                            EcsLogger.debug("com.artipie.maven")
                                .message("Recorded Maven proxy artifact")
                                .eventCategory("repository")
                                .eventAction("proxy_artifact_record")
                                .eventOutcome("success")
                                .field("repository.type", REPO_TYPE)
                                .field("package.name", artifactName)
                                .field("package.version", version)
                                .field("file.size", size)
                                .log();
                        })
                        .exceptionally(err -> {
                            this.handleProcessingError(event, err);
                            return null;
                        });
                } catch (final RuntimeException err) {
                    EcsLogger.error("com.artipie.maven")
                        .message("Failed to extract Maven archive from keys")
                        .eventCategory("repository")
                        .eventAction("proxy_package_process")
                        .eventOutcome("failure")
                        .field("repository.type", REPO_TYPE)
                        .error(err)
                        .log();
                    return CompletableFuture.completedFuture(null);
                }
            })
            .exceptionally(err -> {
                EcsLogger.error("com.artipie.maven")
                    .message("Failed to process Maven package")
                    .eventCategory("repository")
                    .eventAction("proxy_package_process")
                    .eventOutcome("failure")
                    .field("repository.type", REPO_TYPE)
                    .field("package.name", event.artifactKey().string())
                    .error(err)
                    .log();
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
     * Handle processing error with retry logic.
     * Implements retry limits to prevent infinite retry loops for permanently failing packages.
     *
     * @param event Package event that failed
     * @param err Error that occurred
     * @since 1.19.2
     */
    private void handleProcessingError(final ProxyArtifactEvent event, final Throwable err) {
        // If ValueNotFoundException, the file might still be in transit
        // This can happen if file was just moved after listing
        if (err.getCause() instanceof com.artipie.asto.ValueNotFoundException) {
            final String key = event.artifactKey().string();
            final int currentRetries = this.retryCount.getOrDefault(key, 0);

            if (currentRetries < MavenProxyPackageProcessor.MAX_RETRIES) {
                // Increment retry count and re-queue
                this.retryCount.put(key, currentRetries + 1);
                this.packages.add(event);

                EcsLogger.debug("com.artipie.maven")
                    .message("Maven package not found (likely still being written), retrying (attempt " + (currentRetries + 1) + "/" + MavenProxyPackageProcessor.MAX_RETRIES + ")")
                    .eventCategory("repository")
                    .eventAction("proxy_package_retry")
                    .eventOutcome("retry")
                    .field("repository.type", REPO_TYPE)
                    .field("package.name", event.artifactKey().string())
                    .error(err)
                    .log();
            } else {
                // Max retries reached, give up and clean up retry count
                this.retryCount.remove(key);

                EcsLogger.warn("com.artipie.maven")
                    .message("Maven package not found after " + MavenProxyPackageProcessor.MAX_RETRIES + " retries, giving up")
                    .eventCategory("repository")
                    .eventAction("proxy_package_retry")
                    .eventOutcome("abandoned")
                    .field("repository.type", REPO_TYPE)
                    .field("package.name", event.artifactKey().string())
                    .error(err)
                    .log();
            }
        } else {
            EcsLogger.error("com.artipie.maven")
                .message("Failed to read Maven artifact metadata")
                .eventCategory("repository")
                .eventAction("proxy_package_process")
                .eventOutcome("failure")
                .field("repository.type", REPO_TYPE)
                .field("package.name", event.artifactKey().string())
                .error(err)
                .log();
        }
    }
}
