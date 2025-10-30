/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.gradle;

import com.artipie.asto.Key;
import com.artipie.http.log.LogSanitizer;
import com.artipie.asto.Meta;
import com.artipie.asto.Storage;
import com.artipie.asto.ext.KeyLastPart;
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.scheduling.ProxyArtifactEvent;
import com.artipie.scheduling.QuartzJob;
import com.jcabi.log.Logger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.quartz.JobExecutionContext;

/**
 * Processes artifacts uploaded by Gradle proxy and adds info to artifacts metadata events queue.
 * Gradle uses Maven repository format, so we parse artifact coordinates from the path structure.
 * 
 * @since 1.0
 */
public final class GradleProxyPackageProcessor extends QuartzJob {

    /**
     * Repository type.
     */
    private static final String REPO_TYPE = "gradle-proxy";

    /**
     * Pattern to match Gradle/Maven artifact files (jar, aar, war, etc).
     * Matches: groupId/artifactId/version/artifactId-version[-classifier].extension
     */
    private static final Pattern ARTIFACT_PATTERN = 
        Pattern.compile(".*?/([^/]+)/([^/]+)/([^/]+)-\\2(?:-([^.]+))?\\.([^.]+)$");

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
            Logger.warn(this, "Gradle proxy processor not initialized properly - stopping job");
            super.stopJob(context);
        } else {
            Logger.debug(this, "Gradle proxy processor running, queue size: %d", this.packages.size());
            this.processPackagesBatch();
        }
    }

    /**
     * Process packages in parallel batches.
     */
    @SuppressWarnings({"PMD.AssignmentInOperand", "PMD.AvoidCatchingGenericException"})
    private void processPackagesBatch() {
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

        Logger.info(
            this,
            "Processing Gradle batch of %d packages (%d unique, %d duplicates removed)",
            batch.size(), uniquePackages.size(), batch.size() - uniquePackages.size()
        );

        List<CompletableFuture<Void>> futures = uniquePackages.stream()
            .map(this::processPackageAsync)
            .collect(Collectors.toList());

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(30, TimeUnit.SECONDS)
                .join();
            Logger.info(this, "Gradle batch processing complete");
        } catch (final RuntimeException err) {
            Logger.error(this, "Gradle batch processing failed: %s", LogSanitizer.sanitizeMessage(err.getMessage()));
        }
    }

    /**
     * Process a single package asynchronously.
     * @param event Package event
     * @return CompletableFuture
     */
    private CompletableFuture<Void> processPackageAsync(final ProxyArtifactEvent event) {
        final Key key = event.artifactKey();
        Logger.debug(this, "Processing Gradle proxy event for key: %s", key.string());

        return this.asto.list(key).thenCompose(keys -> {
            Logger.debug(this, "Found %d keys under %s", keys.size(), key.string());
            final Key artifactFile = findArtifactFile(keys);
            
            if (artifactFile == null) {
                Logger.warn(
                    this,
                    "No artifact file found for %s (found %d keys), skipping",
                    key.string(),
                    keys.size()
                );
                return CompletableFuture.completedFuture(null);
            }

            final ArtifactCoordinates coords = parseCoordinates(artifactFile);
            if (coords == null) {
                Logger.debug(
                    this,
                    "Could not parse coordinates from %s, skipping",
                    artifactFile.string()
                );
                return CompletableFuture.completedFuture(null);
            }

            return this.asto.metadata(artifactFile)
                .thenApply(meta -> meta.read(Meta.OP_SIZE).get())
                .thenAccept(size -> {
                    final String owner = event.ownerLogin();
                    final long created = System.currentTimeMillis();
                    final Long release = event.releaseMillis().orElse(null);
                    
                    this.events.add(
                        new ArtifactEvent(
                            GradleProxyPackageProcessor.REPO_TYPE,
                            event.repoName(),
                            owner == null || owner.isBlank()
                                ? ArtifactEvent.DEF_OWNER
                                : owner,
                            coords.artifactName(),
                            coords.version(),
                            size,
                            created,
                            release
                        )
                    );
                    
                    Logger.info(
                        this,
                        "Recorded Gradle proxy artifact %s:%s (repo=%s, release=%s)",
                        coords.artifactName(),
                        coords.version(),
                        event.repoName(),
                        release == null ? "unknown" : java.time.Instant.ofEpochMilli(release).toString()
                    );
                });
        }).exceptionally(err -> {
            Logger.error(
                this,
                "Failed to process Gradle package %s: %s",
                key.string(),
                err.getMessage()
            );
            return null;
        });
    }

    /**
     * Find the main artifact file (jar, aar, war) from a list of keys.
     * Excludes POM files, metadata, checksums, and signatures.
     * 
     * @param keys Collection of keys
     * @return Main artifact key or null if not found
     */
    private static Key findArtifactFile(final Collection<Key> keys) {
        return keys.stream()
            .filter(key -> {
                final String name = new KeyLastPart(key).get().toLowerCase(java.util.Locale.ROOT);
                return (name.endsWith(".jar") || name.endsWith(".aar") || name.endsWith(".war"))
                    && !name.endsWith(".pom")
                    && !name.endsWith(".md5")
                    && !name.endsWith(".sha1")
                    && !name.endsWith(".sha256")
                    && !name.endsWith(".sha512")
                    && !name.endsWith(".asc");
            })
            .findFirst()
            .orElse(null);
    }

    /**
     * Parse artifact coordinates from Maven/Gradle path structure.
     * Path format: groupId/artifactId/version/artifactId-version[-classifier].extension
     * 
     * @param key Artifact key
     * @return Artifact coordinates or null if parsing fails
     */
    private static ArtifactCoordinates parseCoordinates(final Key key) {
        final String path = key.string();
        final Matcher matcher = ARTIFACT_PATTERN.matcher(path);
        
        if (!matcher.matches()) {
            return null;
        }

        final String artifactId = matcher.group(1);
        final String version = matcher.group(2);
        
        // Extract groupId from path (everything before artifactId/version)
        final String prefix = path.substring(0, path.indexOf("/" + artifactId + "/" + version));
        final String groupId = prefix.replace('/', '.');
        
        final String artifactName = groupId.isEmpty() ? artifactId : groupId + ":" + artifactId;
        
        return new ArtifactCoordinates(artifactName, version);
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
     * Artifact coordinates holder.
     */
    private static final class ArtifactCoordinates {
        private final String artifactName;
        private final String version;

        ArtifactCoordinates(final String artifactName, final String version) {
            this.artifactName = artifactName;
            this.version = version;
        }

        String artifactName() {
            return this.artifactName;
        }

        String version() {
            return this.version;
        }
    }
}
