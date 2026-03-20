/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.ext.Digests;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.ContentWithSize;
import com.auto1.pantera.http.slice.KeyFromPath;
import com.auto1.pantera.maven.metadata.Version;
import com.auto1.pantera.scheduling.ArtifactEvent;
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
        // Strip semicolon-separated metadata properties from the path to avoid exceeding
        // filesystem filename length limits (typically 255 bytes). These properties are
        // added by JFrog Artifactory and Maven build tools (e.g., vcs.revision, build.timestamp)
        // but are not part of the actual artifact filename.
        final String path = line.uri().getPath();
        final String sanitizedPath;
        final int semicolonIndex = path.indexOf(';');
        if (semicolonIndex > 0) {
            sanitizedPath = path.substring(0, semicolonIndex);
            EcsLogger.debug("com.auto1.pantera.maven")
                .message("Stripped metadata properties from path: " + path + " -> " + sanitizedPath)
                .eventCategory("repository")
                .eventAction("path_sanitization")
                .log();
        } else {
            sanitizedPath = path;
        }

        final Key key = new KeyFromPath(sanitizedPath);
        final String owner = new Login(headers).getValue();
        
        // Get content length from headers for event record
        final long size = headers.stream()
            .filter(h -> "Content-Length".equalsIgnoreCase(h.getKey()))
            .findFirst()
            .map(h -> Long.parseLong(h.getValue()))
            .orElse(0L);
        
        // Track upload metric
        this.recordMetric(() ->
            com.auto1.pantera.metrics.PanteraMetrics.instance().upload(this.rname, "maven")
        );

        // Track bandwidth (upload)
        if (size > 0) {
            this.recordMetric(() ->
                com.auto1.pantera.metrics.PanteraMetrics.instance().bandwidth(this.rname, "maven", "upload", size)
            );
        }
        
        final String keyPath = key.string();
        
        // Special handling for maven-metadata.xml - fix it BEFORE saving
        if (keyPath.contains("maven-metadata.xml") && !keyPath.endsWith(".sha1") && !keyPath.endsWith(".md5")) {
            EcsLogger.debug("com.auto1.pantera.maven")
                .message("Intercepting maven-metadata.xml upload for fixing")
                .eventCategory("repository")
                .eventAction("metadata_upload")
                .field("package.path", keyPath)
                .log();
            return new ContentWithSize(body, headers).asBytesFuture().thenCompose(
                bytes -> this.fixMetadataBytes(bytes).thenCompose(
                    fixedBytes -> {
                        // Save the FIXED metadata
                        return this.storage.save(key, new Content.From(fixedBytes)).thenCompose(
                            nothing -> {
                                EcsLogger.debug("com.auto1.pantera.maven")
                                    .message("Saved fixed maven-metadata.xml, generating checksums")
                                    .eventCategory("repository")
                                    .eventAction("metadata_upload")
                                    .field("package.path", keyPath)
                                    .log();
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
                    EcsLogger.error("com.auto1.pantera.maven")
                        .message("Failed to save artifact")
                        .eventCategory("repository")
                        .eventAction("artifact_upload")
                        .eventOutcome("failure")
                        .error(throwable)
                        .field("package.path", keyPath)
                        .log();
                    return ResponseBuilder.internalError().build();
                }
            );
        }
        
        // For maven-metadata.xml checksums, SKIP them - we generated our own
        if (keyPath.contains("maven-metadata.xml") && (keyPath.endsWith(".sha1") || keyPath.endsWith(".md5") || keyPath.endsWith(".sha256") || keyPath.endsWith(".sha512"))) {
            EcsLogger.debug("com.auto1.pantera.maven")
                .message("Skipping Maven-uploaded checksum for metadata (using generated checksums)")
                .eventCategory("repository")
                .eventAction("checksum_upload")
                .field("package.path", keyPath)
                .log();
            // Don't save Maven's checksums - we already generated correct ones
            return CompletableFuture.completedFuture(ResponseBuilder.created().build());
        }
        
        // Save file first (normal flow for non-metadata files)
        return this.storage.save(key, new ContentWithSize(body, headers)).thenCompose(
            nothing -> {
                EcsLogger.debug("com.auto1.pantera.maven")
                    .message("Saved artifact file")
                    .eventCategory("repository")
                    .eventAction("artifact_upload")
                    .field("package.path", keyPath)
                    .field("package.size", size)
                    .log();

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
                EcsLogger.error("com.auto1.pantera.maven")
                    .message("Failed to save artifact")
                    .eventCategory("repository")
                    .eventAction("artifact_upload")
                    .eventOutcome("failure")
                    .error(throwable)
                    .field("package.path", keyPath)
                    .log();
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
                EcsLogger.debug("com.auto1.pantera.maven")
                    .message("Fixing maven-metadata.xml (" + xml.length() + " bytes)")
                    .eventCategory("repository")
                    .eventAction("metadata_fix")
                    .log();

                final XMLDocument doc = new XMLDocument(xml);
                final List<String> versions = doc.xpath("//version/text()");
                EcsLogger.debug("com.auto1.pantera.maven")
                    .message("Found " + versions.size() + " versions in metadata")
                    .eventCategory("repository")
                    .eventAction("metadata_fix")
                    .log();

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
                    EcsLogger.debug("com.auto1.pantera.maven")
                        .message("Latest version already correct, no update needed")
                        .eventCategory("repository")
                        .eventAction("metadata_fix")
                        .field("package.version", existingLatest)
                        .log();
                    return bytes;
                }

                // Update the <latest> tag
                final String updated = xml.replaceFirst(
                    "<latest>.*?</latest>",
                    "<latest>" + newLatest + "</latest>"
                );

                EcsLogger.debug("com.auto1.pantera.maven")
                    .message("Fixed maven-metadata.xml latest tag: " + existingLatest + " -> " + newLatest)
                    .eventCategory("repository")
                    .eventAction("metadata_fix")
                    .eventOutcome("success")
                    .log();
                return updated.getBytes(StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                EcsLogger.warn("com.auto1.pantera.maven")
                    .message("Failed to parse metadata XML, using original")
                    .eventCategory("repository")
                    .eventAction("metadata_fix")
                    .eventOutcome("failure")
                    .field("error.message", ex.getMessage())
                    .log();
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
            EcsLogger.debug("com.auto1.pantera.maven")
                .message("Skipping metadata/checksum file for event")
                .eventCategory("repository")
                .eventAction("event_creation")
                .field("package.path", path)
                .log();
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
        EcsLogger.debug("com.auto1.pantera.maven")
            .message("Added artifact event")
            .eventCategory("repository")
            .eventAction("event_creation")
            .eventOutcome("success")
            .field("package.name", artifactName)
            .field("package.version", version)
            .field("package.size", size)
            .log();
    }

    /**
     * Record metric safely (only if metrics are enabled).
     * @param metric Metric recording action
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void recordMetric(final Runnable metric) {
        try {
            if (com.auto1.pantera.metrics.PanteraMetrics.isEnabled()) {
                metric.run();
            }
        } catch (final Exception ex) {
            EcsLogger.debug("com.auto1.pantera.maven")
                .message("Failed to record metric")
                .error(ex)
                .log();
        }
    }
}
