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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ValueNotFoundException;
import com.auto1.pantera.asto.ext.KeyLastPart;
import com.auto1.pantera.asto.streams.ContentAsStream;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.pypi.NormalizedProjectName;
import com.auto1.pantera.pypi.http.DistFilename;
import com.auto1.pantera.pypi.meta.Metadata;
import com.auto1.pantera.pypi.meta.PackageInfo;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.scheduling.JobDataRegistry;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import com.auto1.pantera.scheduling.ProxyEventRetry;
import com.auto1.pantera.scheduling.QuartzJob;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.io.input.CloseShieldInputStream;
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
     * Suffixes of the files whose core metadata is read (case as
     * {@link Metadata.FromArchive} compares them): source archives and eggs,
     * whose names can be ambiguous. Any other file is known by its name,
     * wheels included: a wheel's name is normative (PEP 427, PEP 491), and
     * reading it would stream the whole file, a full GET on S3, for what the
     * name already says.
     */
    private static final List<String> READABLE = List.of(
        ".egg", ".zip", ".tar", ".tar.gz", ".tar.Z", ".tar.bz2"
    );

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
            super.stopJob(context);
        } else {
            this.processPackagesBatch();
        }
    }

    /**
     * Process packages in parallel batches.
     */
    private void processPackagesBatch() {
        // Events waiting for their retry time stay queued (ProxyEventRetry).
        final List<ProxyArtifactEvent> batch = new ProxyEventRetry(this.packages).due(100);

        if (batch.isEmpty()) {
            return;
        }

        final long startTime = System.currentTimeMillis();
        EcsLogger.debug("com.auto1.pantera.pypi")
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
            EcsLogger.debug("com.auto1.pantera.pypi")
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
     * Process a single package asynchronously. The size of the file comes
     * from the storage; the file itself is read only for its core metadata,
     * as a stream, and not at all when its name is all that is known of it.
     * @param event Package event
     * @return CompletableFuture
     */
    private CompletableFuture<Void> processPackageAsync(final ProxyArtifactEvent event) {
        final Key key = event.artifactKey();
        return this.asto.metadata(key).handle(
            (meta, err) -> {
                final CompletableFuture<Void> res;
                if (err == null) {
                    res = this.record(
                        event, meta.read(Meta.OP_SIZE).map(Long::longValue).orElse(0L)
                    );
                } else if (PyProxyPackageProcessor.absent(err)) {
                    // The stream-through cache commits the file only when the
                    // client has read the body, which is after the event was
                    // queued; and it never commits when the client hangs up.
                    // Bounded retry with backoff: DEBUG while retrying, one
                    // WARN when given up.
                    new ProxyEventRetry(this.packages).again(event, "com.auto1.pantera.pypi");
                    res = CompletableFuture.completedFuture(null);
                } else {
                    res = CompletableFuture.failedFuture(err);
                }
                return res;
            }
        ).thenCompose(Function.identity()).exceptionally(err -> {
            EcsLogger.error("com.auto1.pantera.pypi")
                .traceContext(event.traceId(), event.clientIp())
                .field("repository.name", event.repoName())
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
     * Record the release of a stored file.
     * @param event Package event
     * @param size Size of the stored file
     * @return Completes when the release is recorded, or found not to be one
     */
    private CompletableFuture<Void> record(final ProxyArtifactEvent event, final long size) {
        final Key key = event.artifactKey();
        final String filename = new KeyLastPart(key).get();
        return this.release(key, filename).handle(
            (found, err) -> {
                if (err != null) {
                    EcsLogger.error("com.auto1.pantera.pypi")
                        .traceContext(event.traceId(), event.clientIp())
                        .field("repository.name", event.repoName())
                        .message("Failed to parse/check python proxy package")
                        .eventCategory("web")
                        .eventAction("package_processing")
                        .eventOutcome("failure")
                        .field("package.name", key.string())
                        .error(err)
                        .field("log.source", "application")
                        .log();
                } else if (found.isPresent()) {
                    this.recorded(event, found.get(), size);
                } else {
                    EcsLogger.warn("com.auto1.pantera.pypi")
                        .traceContext(event.traceId(), event.clientIp())
                        .field("repository.name", event.repoName())
                        .message("Python proxy package failed filename validation")
                        .eventCategory("package")
                        .eventAction("package_processing")
                        .eventOutcome("failure")
                        .field(
                            "event.reason",
                            "the filename does not name the project and version of its metadata"
                        )
                        .field("file.name", filename)
                        .field("log.source", "application")
                        .log();
                }
                return null;
            }
        );
    }

    /**
     * Add the artifact event of a release.
     * @param event Package event
     * @param found Project and version
     * @param size Size of the stored file
     */
    private void recorded(final ProxyArtifactEvent event, final Release found, final long size) {
        final String owner = event.ownerLogin();
        final Long release = event.releaseMillis().orElse(null);
        this.events.add(
            new ArtifactEvent(
                PyProxyPackageProcessor.REPO_TYPE,
                event.repoName(),
                owner == null || owner.isBlank() ? ArtifactEvent.DEF_OWNER : owner,
                found.project(),
                found.version(),
                size,
                System.currentTimeMillis(),
                release,
                event.artifactKey().string()
            ).withContext(event.traceId(), event.clientIp())
        );
        EcsLogger.info("com.auto1.pantera.pypi")
            .traceContext(event.traceId(), event.clientIp())
            .message("Recorded PyPI proxy release")
            .eventCategory("web")
            .eventAction("package_processing")
            .eventOutcome("success")
            .field("package.name", found.project())
            .field("package.version", found.version())
            .field("repository.name", event.repoName())
            .field("package.size", size)
            .field("package.release_date", release == null ? null
                : Instant.ofEpochMilli(release).toString())
            .field("log.source", "application")
            .log();
    }

    /**
     * Project and version of a stored distribution file. For the source
     * archives and eggs of {@link #READABLE} they come from the core metadata
     * in the file, read as a stream, and must be the ones its name says. The
     * other files PyPI has served (wheels, {@code .tgz}, {@code .tbz},
     * {@code .tar.xz}, {@code .txz}, Windows installers, RPM packages, disk
     * images, Debian packages) are known by their name and not read.
     *
     * @param key Storage key of the file
     * @param filename Name of the file
     * @return Normalized project and version, empty when the name says
     *  neither or disagrees with the metadata
     */
    private CompletableFuture<Optional<Release>> release(final Key key, final String filename) {
        final DistFilename dist = new DistFilename(filename);
        final Optional<Release> named = dist.project().flatMap(
            project -> dist.version().map(version -> new Release(project, version))
        );
        final CompletableFuture<Optional<Release>> res;
        if (PyProxyPackageProcessor.READABLE.stream().anyMatch(filename::endsWith)) {
            res = this.asto.value(key)
                .thenCompose(
                    content -> new ContentAsStream<PackageInfo>(content)
                        .process(PyProxyPackageProcessor.reader(filename))
                )
                .thenApply(
                    info -> named.filter(
                        new Release(
                            new NormalizedProjectName.Simple(info.name()).value(),
                            info.version()
                        )::equals
                    )
                );
        } else {
            res = CompletableFuture.completedFuture(named);
        }
        return res;
    }

    /**
     * Reader of the core metadata of a file streamed from the storage. The
     * archive reader stops after the last entry, before the end of the file
     * (a zip's central directory, a tar's end padding); the rest is read so
     * that the stream feeding it completes. The archive reader closes what
     * it reads, which must not close the stream before that.
     * @param filename Name of the file
     * @return Reader
     */
    private static Function<InputStream, PackageInfo> reader(final String filename) {
        return input -> {
            final PackageInfo info = new Metadata.FromArchive(
                CloseShieldInputStream.wrap(input), filename
            ).read();
            try {
                input.transferTo(OutputStream.nullOutputStream());
            } catch (final IOException ex) {
                throw new PanteraIOException(ex);
            }
            return info;
        };
    }

    /**
     * Whether a storage failure says the file is not there (yet).
     * @param err Failure
     * @return True when any cause is a missing value or file
     */
    private static boolean absent(final Throwable err) {
        Throwable cause = err;
        boolean found = false;
        while (cause != null && !found) {
            found = cause instanceof ValueNotFoundException
                || cause instanceof NoSuchFileException;
            cause = cause.getCause();
        }
        return found;
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
     * Project and version of a distribution file.
     * @param project Normalized project name
     * @param version Version
     */
    private record Release(String project, String version) {
    }
}
