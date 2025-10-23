/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.events;

import com.artipie.asto.Key;
import com.artipie.asto.Meta;
import com.artipie.asto.Storage;
import com.artipie.asto.streams.ContentAsStream;
import com.artipie.npm.Publish;
import com.artipie.npm.TgzArchive;
import com.artipie.npm.http.UploadSlice;
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
import java.util.stream.Collectors;
import javax.json.Json;
import javax.json.JsonObject;
import org.quartz.JobExecutionContext;

/**
 * We can assume that repository actually contains some package, if:
 * <br/>
 * 1) tgz archive is valid and we obtained package id and version from it<br/>
 * 2) repository has corresponding package json metadata file with such version and
 *   path to tgz
 * <br/>
 * When both conditions a met, we can add package record into database.
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
     * Artipie host (host only).
     */
    private String host;

    @Override
    @SuppressWarnings("PMD.CyclomaticComplexity")
    public void execute(final JobExecutionContext context) {
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
        final List<ProxyArtifactEvent> batch = new ArrayList<>(100);
        ProxyArtifactEvent item;
        while (batch.size() < 100 && (item = this.packages.poll()) != null) {
            batch.add(item);
        }

        if (batch.isEmpty()) {
            return;
        }

        Logger.info(this, "Processing NPM batch of %d packages", batch.size());

        List<CompletableFuture<Void>> futures = batch.stream()
            .map(this::processPackageAsync)
            .collect(Collectors.toList());

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(30, TimeUnit.SECONDS)
                .join();
            Logger.info(this, "NPM batch processing complete");
        } catch (Exception err) {
            Logger.error(this, "NPM batch processing failed: %s", err.getMessage());
        }
    }

    /**
     * Process a single package asynchronously.
     * @param item Package event
     * @return CompletableFuture
     */
    private CompletableFuture<Void> processPackageAsync(final ProxyArtifactEvent item) {
        return this.infoAsync(item.artifactKey())
            .thenCompose(info -> {
                if (info.isEmpty()) {
                    Logger.info(this, "Package %s has no info", item.artifactKey().string());
                    return CompletableFuture.completedFuture(null);
                }
                return this.checkMetadataAsync(info.get(), item)
                    .thenAccept(valid -> {
                        if (valid) {
                            final long created = System.currentTimeMillis();
                            final Long release = item.releaseMillis().orElse(null);
                            this.events.add(
                                new ArtifactEvent(
                                    UploadSlice.REPO_TYPE, item.repoName(), item.ownerLogin(),
                                    info.get().packageName(), info.get().packageVersion(),
                                    info.get().tarSize(), created, release
                                )
                            );
                        } else {
                            Logger.info(this, "Package %s is not valid", item.artifactKey().string());
                        }
                    });
            })
            .exceptionally(err -> {
                Logger.error(this, "Failed to process NPM package: %s", err.getMessage());
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
     * Method checks that package metadata contains version from package info and
     * path in `dist` fiend to corresponding tgz package.
     * @param info Info from tgz to check
     * @param item Item with tgz file key path in storage
     * @return True, if package meta.jaon metadata contains the version and path
     */
    private boolean checkMetadata(final Publish.PackageInfo info, final ProxyArtifactEvent item) {
        final Key key = new Key.From(info.packageName(), "meta.json");
        return this.asto.value(key)
            .thenCompose(
                content -> new ContentAsStream<>(content).process(
                    input -> Json.createReader(input).readObject()
                )
            ).thenApply(
                json -> {
                    final JsonObject version = ((JsonObject) json).getJsonObject("versions")
                        .getJsonObject(info.packageVersion());
                    boolean res = false;
                    if (version != null) {
                        final JsonObject dist = version.getJsonObject("dist");
                        if (dist != null) {
                            final String tarball = dist.getString("tarball");
                            res = tarball.equals(String.format("/%s", item.artifactKey().string()))
                                || tarball.contains(
                                    String.join(
                                        "/", this.host, item.repoName(), item.artifactKey().string()
                                    )
                                );
                        }
                    }
                    return res;
                }
            ).handle(
                (correct, error) -> {
                    final boolean res;
                    if (error == null) {
                        res = correct;
                    } else {
                        Logger.error(
                            this,
                            String.format(
                                "Error while checking %s for dist %s \n%s",
                                key.string(), item.artifactKey().string(), error.getMessage()
                            )
                        );
                        res = false;
                    }
                    return res;
                }
            ).join();
    }

    /**
     * Read package info asynchronously.
     * @param tgz Tgz storage key
     * @return CompletableFuture with package info
     */
    private CompletableFuture<Optional<Publish.PackageInfo>> infoAsync(final Key tgz) {
        return this.asto.value(tgz).thenCompose(
            content -> new ContentAsStream<>(content).<JsonObject>process(
                input -> new TgzArchive.JsonFromStream(input).json()
            )
        ).thenCombine(
            this.asto.metadata(tgz).thenApply(meta -> meta.read(Meta.OP_SIZE).get()),
            (json, size) -> new Publish.PackageInfo(
                ((JsonObject) json).getString("name"),
                ((JsonObject) json).getString("version"), size
            )
        ).handle(
            (info, error) -> {
                if (error == null) {
                    return Optional.of(info);
                } else {
                    Logger.error(
                        this,
                        "Error while reading tgz %s info: %s",
                        tgz.string(), error.getMessage()
                    );
                    return Optional.<Publish.PackageInfo>empty();
                }
            }
        );
    }

    /**
     * Check metadata asynchronously.
     * @param info Package info
     * @param item Proxy artifact event
     * @return CompletableFuture with validation result
     */
    private CompletableFuture<Boolean> checkMetadataAsync(
        final Publish.PackageInfo info,
        final ProxyArtifactEvent item
    ) {
        final Key key = new Key.From(info.packageName(), "meta.json");
        return this.asto.value(key)
            .thenCompose(
                content -> new ContentAsStream<>(content).process(
                    input -> Json.createReader(input).readObject()
                )
            ).thenApply(obj -> {
                final JsonObject json = (JsonObject) obj;
                // Check if metadata contains the version and dist.tarball
                boolean correct = false;
                if (json.containsKey("versions")) {
                    final JsonObject versions = json.getJsonObject("versions");
                    if (versions.containsKey(info.packageVersion())) {
                        final JsonObject version = versions.getJsonObject(info.packageVersion());
                        if (version.containsKey("dist")) {
                            final JsonObject dist = version.getJsonObject("dist");
                            if (dist.containsKey("tarball")) {
                                final String tarball = dist.getString("tarball");
                                final String artifactPath = item.artifactKey().string();
                                // Check if tarball URL ends with the artifact path
                                // This handles various URL formats (absolute, relative, with/without host)
                                correct = tarball.endsWith(artifactPath) || 
                                         tarball.equals(String.format("%s/%s", this.host, artifactPath));
                            }
                        }
                    }
                }
                return correct;
            }).handle((correct, error) -> {
                if (error == null) {
                    return correct;
                } else {
                    Logger.error(
                        this,
                        "Error while checking %s for dist %s: %s",
                        key.string(), item.artifactKey().string(), error.getMessage()
                    );
                    return false;
                }
            });
    }

}
