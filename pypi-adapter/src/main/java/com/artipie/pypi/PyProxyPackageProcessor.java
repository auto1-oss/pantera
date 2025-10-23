/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.pypi;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.ext.KeyLastPart;
import com.artipie.pypi.NormalizedProjectName;
import com.artipie.pypi.meta.Metadata;
import com.artipie.pypi.meta.PackageInfo;
import com.artipie.pypi.meta.ValidFilename;
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.scheduling.ProxyArtifactEvent;
import com.artipie.scheduling.QuartzJob;
import com.jcabi.log.Logger;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.quartz.JobExecutionContext;

/**
 * Job to process package, loaded via proxy and add corresponding info to
 * events queue.
 * @since 0.9
 */
public final class PyProxyPackageProcessor extends QuartzJob {

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
    @SuppressWarnings({"PMD.AvoidCatchingGenericException"})
    public void execute(final JobExecutionContext context) {
        if (this.asto == null || this.packages == null || this.events == null) {
            super.stopJob(context);
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

        Logger.info(this, "Processing PyPI batch of %d packages", batch.size());

        List<CompletableFuture<Void>> futures = batch.stream()
            .map(this::processPackageAsync)
            .collect(Collectors.toList());

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(30, TimeUnit.SECONDS)
                .join();
            Logger.info(this, "PyPI batch processing complete");
        } catch (Exception err) {
            Logger.error(this, "PyPI batch processing failed: %s", err.getMessage());
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
                Logger.debug(
                    this,
                    "Artifact %s is not yet cached, re-queuing for retry",
                    key.string()
                );
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
                                    release
                                )
                            );

                            Logger.info(
                                this,
                                "Recorded PyPI proxy release %s:%s (repo=%s, size=%dB, release=%s)",
                                project,
                                info.version(),
                                event.repoName(),
                                archive.length,
                                release == null ? "unknown"
                                    : Instant.ofEpochMilli(release).toString()
                            );
                        } else {
                            Logger.error(
                                this,
                                "Python proxy package %s is not valid", key.string()
                            );
                        }
                    } catch (final Exception err) {
                        Logger.error(
                            this,
                            "Failed to parse/check python proxy package %s: %s",
                            key.string(), err.getMessage()
                        );
                    }
                });
        }).exceptionally(err -> {
            Logger.error(
                this,
                "Failed to process PyPI package %s: %s",
                key.string(), err.getMessage()
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
