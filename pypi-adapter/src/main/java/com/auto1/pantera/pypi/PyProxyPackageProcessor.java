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
package com.auto1.pantera.pypi;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ext.KeyLastPart;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.pypi.NormalizedProjectName;
import com.auto1.pantera.pypi.meta.Metadata;
import com.auto1.pantera.pypi.meta.PackageInfo;
import com.auto1.pantera.pypi.meta.ValidFilename;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Job to process package, loaded via proxy and add corresponding info to
 * events queue.
 * <p>
 * Runs as a per-node periodic {@link Runnable} tick (see
 * {@code MetadataEventQueues}/{@code LocalEventDrainScheduler}) — never
 * through the cluster-shared Quartz job store; see {@code GoProxyPackageProcessor}
 * for the WS2.2b rationale.
 * @since 0.9
 */
public final class PyProxyPackageProcessor implements Runnable {

    /**
     * Repository type.
     */
    private static final String REPO_TYPE = "pypi-proxy";

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
    public void run() {
        if (this.asto == null || this.packages == null || this.events == null) {
            EcsLogger.error("com.auto1.pantera.pypi")
                .message("PyPI proxy processor not initialized properly")
                .eventCategory("web")
                .eventAction("batch_processing")
                .eventOutcome("failure")
                .field("log.source", "application")
                .log();
        } else {
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

        final long startTime = System.currentTimeMillis();
        EcsLogger.info("com.auto1.pantera.pypi")
            .message("Processing PyPI batch (size: " + batch.size() + ")")
            .eventCategory("web")
            .eventAction("batch_processing")
            .field("log.source", "application")
            .log();

        List<CompletableFuture<Void>> futures = batch.stream()
            .map(this::processPackageAsync)
            .collect(Collectors.toList());

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(30, TimeUnit.SECONDS)
                .join();
            final long duration = System.currentTimeMillis() - startTime;
            EcsLogger.info("com.auto1.pantera.pypi")
                .message("PyPI batch processing complete (size: " + batch.size() + ")")
                .eventCategory("web")
                .eventAction("batch_processing")
                .eventOutcome("success")
                .duration(duration)
                .field("log.source", "application")
                .log();
        } catch (Exception err) {
            final long duration = System.currentTimeMillis() - startTime;
            EcsLogger.error("com.auto1.pantera.pypi")
                .message("PyPI batch processing failed (size: " + batch.size() + ")")
                .eventCategory("web")
                .eventAction("batch_processing")
                .eventOutcome("failure")
                .duration(duration)
                .error(err)
                .field("log.source", "application")
                .log();
        }
    }

    /**
     * Process a single package asynchronously.
     * @param event Package event
     * @return CompletableFuture
     */
    private CompletableFuture<Void> processPackageAsync(final ProxyArtifactEvent event) {
        final Key key = event.artifactKey();
        final String filename = new KeyLastPart(key).get();

        return this.asto.exists(key).thenCompose(exists -> {
            if (!exists) {
                EcsLogger.debug("com.auto1.pantera.pypi")
                    .message("Artifact not yet cached, re-queuing for retry")
                    .eventCategory("web")
                    .eventAction("package_processing")
                    .field("package.name", key.string())
                    .field("log.source", "application")
                    .log();
                // Re-add event to queue for retry
                this.packages.add(event);
                return CompletableFuture.completedFuture(null);
            }

            return this.asto.value(key)
                .thenCompose(content -> new Content.From(content).asBytesFuture())
                .thenAccept(archive -> {
                    try {
                        final PackageInfo info = new Metadata.FromArchive(
                            new ByteArrayInputStream(archive), filename
                        ).read();

                        if (new ValidFilename(info, filename).valid()) {
                            final String owner = event.ownerLogin();
                            final long created = System.currentTimeMillis();
                            final Long release = event.releaseMillis().orElse(null);
                            final String project =
                                new NormalizedProjectName.Simple(info.name()).value();

                            this.events.add(
                                new ArtifactEvent(
                                    PyProxyPackageProcessor.REPO_TYPE,
                                    event.repoName(),
                                    owner == null || owner.isBlank()
                                        ? ArtifactEvent.DEF_OWNER
                                        : owner,
                                    project,
                                    info.version(),
                                    archive.length,
                                    created,
                                    release,
                                    event.artifactKey().string()
                                ).withContext(event.traceId(), event.clientIp())
                            );

                            EcsLogger.info("com.auto1.pantera.pypi")
                                .message("Recorded PyPI proxy release")
                                .eventCategory("web")
                                .eventAction("package_processing")
                                .eventOutcome("success")
                                .field("package.name", project)
                                .field("package.version", info.version())
                                .field("repository.name", event.repoName())
                                .field("package.size", archive.length)
                                .field("package.release_date", release == null ? null
                                    : Instant.ofEpochMilli(release).toString())
                                .field("log.source", "application")
                                .log();
                        } else {
                            EcsLogger.warn("com.auto1.pantera.pypi")
                                .message("Python proxy package failed filename validation")
                                .eventCategory("package")
                                .eventAction("package_processing")
                                .eventOutcome("failure")
                                .field("event.reason", "filename did not match WHEEL_PTRN or ARCHIVE_PTRN")
                                .field("file.name", filename)
                                .field("log.source", "application")
                                .log();
                        }
                    } catch (final Exception err) {
                        EcsLogger.error("com.auto1.pantera.pypi")
                            .message("Failed to parse/check python proxy package")
                            .eventCategory("web")
                            .eventAction("package_processing")
                            .eventOutcome("failure")
                            .field("package.name", key.string())
                            .error(err)
                            .field("log.source", "application")
                            .log();
                    }
                });
        }).exceptionally(err -> {
            EcsLogger.error("com.auto1.pantera.pypi")
                .message("Failed to process PyPI package")
                .eventCategory("web")
                .eventAction("package_processing")
                .eventOutcome("failure")
                .field("package.name", key.string())
                .error(err)
                .field("log.source", "application")
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

}
