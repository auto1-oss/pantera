/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.ext.ContentDigest;
import com.artipie.asto.ext.Digests;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.headers.Login;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.slice.ContentWithSize;
import com.artipie.http.slice.KeyFromPath;
import com.artipie.maven.metadata.Version;
import com.artipie.scheduling.ArtifactEvent;
import com.jcabi.log.Logger;
import com.jcabi.xml.XMLDocument;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;

/**
 * Simple upload slice that saves files directly to storage, similar to Gradle adapter.
 * No temporary directories, no complex validation - just save and optionally emit events.
 * @since 0.8
 */
public final class UploadSlice implements Slice {

    /**
     * Supported checksum algorithms.
     */
    private static final List<String> CHECKSUM_ALGS = Arrays.asList("sha512", "sha256", "sha1", "md5");

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Artifact events queue.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String rname;

    /**
     * Ctor without events.
     * @param storage Abstract storage
     */
    public UploadSlice(final Storage storage) {
        this(storage, Optional.empty(), "maven");
    }

    /**
     * Ctor with events.
     * @param storage Storage
     * @param events Artifact events queue
     * @param rname Repository name
     */
    public UploadSlice(
        final Storage storage,
        final Optional<Queue<ArtifactEvent>> events,
        final String rname
    ) {
        this.storage = storage;
        this.events = events;
        this.rname = rname;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final Key key = new KeyFromPath(line.uri().getPath());
        final String owner = new Login(headers).getValue();
        
        // Get content length from headers for event record
        final long size = headers.stream()
            .filter(h -> "Content-Length".equalsIgnoreCase(h.getKey()))
            .findFirst()
            .map(h -> Long.parseLong(h.getValue()))
            .orElse(0L);
        
        // Track upload metric
        this.recordMetric(() -> 
            com.artipie.metrics.ArtipieMetrics.instance().upload("maven")
        );
        
        // Track bandwidth (upload)
        if (size > 0) {
            this.recordMetric(() -> 
                com.artipie.metrics.ArtipieMetrics.instance().bandwidth("maven", "upload", size)
            );
        }
        
        final String keyPath = key.string();
        
        // Special handling for maven-metadata.xml - fix it BEFORE saving
        if (keyPath.contains("maven-metadata.xml") && !keyPath.endsWith(".sha1") && !keyPath.endsWith(".md5")) {
            Logger.info(this, "Intercepting maven-metadata.xml upload at %s", keyPath);
            return new ContentWithSize(body, headers).asBytesFuture().thenCompose(
                bytes -> this.fixMetadataBytes(bytes).thenCompose(
                    fixedBytes -> {
                        // Save the FIXED metadata
                        return this.storage.save(key, new Content.From(fixedBytes)).thenCompose(
                            nothing -> {
                                Logger.info(this, "Saved fixed maven-metadata.xml, generating checksums");
                                // Generate checksums for the fixed content
                                return this.generateChecksums(key);
                            }
                        );
                    }
                )
            ).thenApply(
                nothing -> {
                    this.addEvent(key, owner, size);
                    return ResponseBuilder.created().build();
                }
            ).exceptionally(
                throwable -> {
                    Logger.error(this, "Failed to save artifact: %s", throwable.getMessage());
                    return ResponseBuilder.internalError().build();
                }
            );
        }
        
        // For maven-metadata.xml checksums, SKIP them - we generated our own
        if (keyPath.contains("maven-metadata.xml") && (keyPath.endsWith(".sha1") || keyPath.endsWith(".md5") || keyPath.endsWith(".sha256") || keyPath.endsWith(".sha512"))) {
            Logger.info(this, "Skipping Maven-uploaded checksum for metadata: %s", keyPath);
            // Don't save Maven's checksums - we already generated correct ones
            return CompletableFuture.completedFuture(ResponseBuilder.created().build());
        }
        
        // Save file first (normal flow for non-metadata files)
        return this.storage.save(key, new ContentWithSize(body, headers)).thenCompose(
            nothing -> {
                Logger.debug(this, "Saved file: %s", keyPath);
                
                // For non-metadata/checksum files, generate checksums
                if (this.shouldGenerateChecksums(key)) {
                    return this.generateChecksums(key);
                } else {
                    return CompletableFuture.completedFuture(null);
                }
            }
        ).thenApply(
            nothing -> {
                this.addEvent(key, owner, size);
                return ResponseBuilder.created().build();
            }
        ).exceptionally(
            throwable -> {
                Logger.error(this, "Failed to save artifact: %s", throwable.getMessage());
                return ResponseBuilder.internalError().build();
            }
        );
    }

    /**
     * Fix maven-metadata.xml bytes to ensure <latest> tag is correct.
     * Reads all versions and sets <latest> to the highest version.
     * @param bytes Original metadata XML bytes
     * @return Completable future with fixed bytes
     */
    private CompletableFuture<byte[]> fixMetadataBytes(final byte[] bytes) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                final String xml = new String(bytes, StandardCharsets.UTF_8);
                Logger.info(this, "Fixing maven-metadata.xml, length: %d", xml.length());
                
                final XMLDocument doc = new XMLDocument(xml);
                final List<String> versions = doc.xpath("//version/text()");
                Logger.info(this, "Found %d versions in metadata", versions.size());
                
                if (versions.isEmpty()) {
                    return bytes; // No versions, return unchanged
                }
                
                // Find the highest version from all versions
                final String highestVersion = versions.stream()
                    .max(Comparator.comparing(Version::new))
                    .orElse(versions.get(versions.size() - 1));
                
                // Get current <latest> tag value
                final List<String> currentLatest = doc.xpath("//latest/text()");
                final String existingLatest = currentLatest.isEmpty() ? null : currentLatest.get(0);
                
                // Only update if the highest version is actually newer than existing latest
                final String newLatest;
                if (existingLatest == null || existingLatest.isEmpty()) {
                    newLatest = highestVersion;
                } else {
                    // Compare versions - only update if new version is higher
                    final Version existing = new Version(existingLatest);
                    final Version highest = new Version(highestVersion);
                    newLatest = highest.compareTo(existing) > 0 ? highestVersion : existingLatest;
                }
                
                // Check if we need to update
                if (newLatest.equals(existingLatest)) {
                    Logger.info(this, "Latest version %s is already correct, no update needed", existingLatest);
                    return bytes;
                }
                
                // Update the <latest> tag
                final String updated = xml.replaceFirst(
                    "<latest>.*?</latest>",
                    "<latest>" + newLatest + "</latest>"
                );
                
                Logger.info(this, "Fixed maven-metadata.xml: <latest> updated from %s to %s", existingLatest, newLatest);
                return updated.getBytes(StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                Logger.warn(this, "Failed to parse metadata XML: %s", ex.getMessage());
                return bytes; // Return unchanged on error
            }
        });
    }

    /**
     * Check if we should generate checksums for this file.
     * Don't generate checksums for checksum files themselves.
     * @param key File key
     * @return True if checksums should be generated
     */
    private boolean shouldGenerateChecksums(final Key key) {
        final String path = key.string();
        return !path.endsWith(".md5") 
            && !path.endsWith(".sha1") 
            && !path.endsWith(".sha256") 
            && !path.endsWith(".sha512");
    }

    /**
     * Generate checksum files by reading the file from storage.
     * @param key Original file key
     * @return Completable future
     */
    private CompletableFuture<Void> generateChecksums(final Key key) {
        return CompletableFuture.allOf(
            CHECKSUM_ALGS.stream().map(
                alg -> this.storage.value(key).thenCompose(
                    content -> new ContentDigest(
                        content, Digests.valueOf(alg.toUpperCase(Locale.US))
                    ).hex()
                ).thenCompose(
                    hex -> this.storage.save(
                        new Key.From(String.format("%s.%s", key.string(), alg)),
                        new Content.From(hex.getBytes(StandardCharsets.UTF_8))
                    )
                ).toCompletableFuture()
            ).toArray(CompletableFuture[]::new)
        );
    }

    /**
     * Add artifact event to queue for actual artifacts (not metadata/checksums).
     * @param key Artifact key
     * @param owner Owner
     * @param size Artifact size
     */
    private void addEvent(final Key key, final String owner, final long size) {
        if (this.events.isEmpty()) {
            return;
        }
        
        final String path = key.string().startsWith("/") ? key.string() : "/" + key.string();
        
        // Skip metadata and checksum files
        if (this.isMetadataOrChecksum(path)) {
            Logger.debug(this, "Skipping metadata/checksum file for event: %s", path);
            return;
        }
        
        final Matcher matcher = MavenSlice.ARTIFACT.matcher(path);
        if (matcher.matches()) {
            this.createAndAddEvent(matcher.group("pkg"), owner, size);
        }
    }

    /**
     * Check if path is metadata or checksum file.
     * @param path File path
     * @return True if metadata or checksum
     */
    private boolean isMetadataOrChecksum(final String path) {
        return path.contains("maven-metadata.xml") 
            || path.endsWith(".md5") 
            || path.endsWith(".sha1") 
            || path.endsWith(".sha256") 
            || path.endsWith(".sha512");
    }

    /**
     * Create and add artifact event from package path.
     * @param pkg Package path (group/artifact/version)
     * @param owner Owner
     * @param size Artifact size
     */
    private void createAndAddEvent(final String pkg, final String owner, final long size) {
        // Extract version (last directory before the file)
        final String[] parts = pkg.split("/");
        final String version = parts.length > 0 ? parts[parts.length - 1] : "unknown";
        
        // Remove version from pkg to get group/artifact only
        String groupArtifact = pkg.substring(0, pkg.lastIndexOf('/'));
        
        // Remove leading slash if present
        if (groupArtifact.startsWith("/")) {
            groupArtifact = groupArtifact.substring(1);
        }
        
        // Format artifact name as group.artifact (replacing / with .)
        final String artifactName = MavenSlice.EVENT_INFO.formatArtifactName(groupArtifact);
        
        this.events.get().add(
            new ArtifactEvent(
                "maven",
                this.rname,
                owner == null || owner.isBlank() ? ArtifactEvent.DEF_OWNER : owner,
                artifactName,
                version,
                size,
                System.currentTimeMillis(),
                (Long) null  // No release date for uploads
            )
        );
        Logger.info(
            this,
            "Added artifact event: %s:%s (size=%d)",
            artifactName, version, size
        );
    }

    /**
     * Record metric safely (only if metrics are enabled).
     * @param metric Metric recording action
     */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.EmptyCatchBlock"})
    private void recordMetric(final Runnable metric) {
        try {
            if (com.artipie.metrics.ArtipieMetrics.isEnabled()) {
                metric.run();
            }
        } catch (final Exception ex) {
            // Ignore metric errors - don't fail requests
        }
    }
}
