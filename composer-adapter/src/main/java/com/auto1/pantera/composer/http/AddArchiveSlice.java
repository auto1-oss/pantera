/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.composer.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.composer.Repository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.scheduling.ArtifactEvent;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slice for adding a package to the repository in ZIP format.
 * Accepts any .zip file and extracts metadata from composer.json inside.
 * See <a href="https://getcomposer.org/doc/05-repositories.md#artifact">Artifact repository</a>.
 */
@SuppressWarnings({"PMD.SingularField", "PMD.UnusedPrivateField"})
final class AddArchiveSlice implements Slice {
    /**
     * Repository type.
     */
    public static final String REPO_TYPE = "php";

    /**
     * Repository.
     */
    private final Repository repository;

    /**
     * Artifact events.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String rname;

    /**
     * Ctor.
     * @param repository Repository.
     * @param rname Repository name
     */
    AddArchiveSlice(final Repository repository, final String rname) {
        this(repository, Optional.empty(), rname);
    }

    /**
     * Ctor.
     * @param repository Repository
     * @param events Artifact events
     * @param rname Repository name
     */
    AddArchiveSlice(
        final Repository repository, final Optional<Queue<ArtifactEvent>> events,
        final String rname
    ) {
        this.repository = repository;
        this.events = events;
        this.rname = rname;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final String uri = line.uri().getPath();
        
        // Validate path doesn't contain directory traversal
        if (uri.contains("..")) {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Rejected archive path with directory traversal")
                .eventCategory("repository")
                .eventAction("archive_upload")
                .eventOutcome("failure")
                .field("url.path", uri)
                .log();
            return ResponseBuilder.badRequest()
                .textBody("Path traversal not allowed")
                .completedFuture();
        }

        // Validate archive format - support .zip, .tar.gz, .tgz
        final String lowerUri = uri.toLowerCase();
        final boolean isZip = lowerUri.endsWith(".zip");
        final boolean isTarGz = lowerUri.endsWith(".tar.gz") || lowerUri.endsWith(".tgz");

        if (!isZip && !isTarGz) {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Rejected unsupported archive format")
                .eventCategory("repository")
                .eventAction("archive_upload")
                .eventOutcome("failure")
                .field("url.path", uri)
                .log();
            return ResponseBuilder.badRequest()
                .textBody("Only .zip, .tar.gz, and .tgz archives are supported for Composer packages")
                .completedFuture();
        }
        
        // Extract the filename from the URI for initial storage
        final String filename = uri.substring(uri.lastIndexOf('/') + 1);
        
        // First, extract composer.json to get the real package metadata
        return body.asBytesFuture().thenCompose(bytes -> {
            // Choose appropriate archive handler based on format
            final Archive tempArchive = isZip
                ? new Archive.Zip(new Archive.Name(filename, "unknown"))
                : new TarArchive(new Archive.Name(filename, "unknown"));
            
            return tempArchive.composerFrom(new Content.From(bytes))
                .thenCompose(composerJson -> {
                    // Extract name and version from composer.json (source of truth)
                    final String packageName = composerJson.getString("name", null);
                    if (packageName == null || packageName.trim().isEmpty()) {
                        EcsLogger.warn("com.auto1.pantera.composer")
                            .message("Missing or empty 'name' in composer.json")
                            .eventCategory("repository")
                            .eventAction("archive_upload")
                            .eventOutcome("failure")
                            .field("url.path", uri)
                            .log();
                        return CompletableFuture.completedFuture(
                            ResponseBuilder.badRequest()
                                .textBody("composer.json must contain non-empty 'name' field")
                                .build()
                        );
                    }
                    
                    // Handle version - try multiple sources in priority order:
                    // 1. composer.json version field
                    // 2. Extract from filename (e.g., package-1.0.0.tar.gz)
                    // 3. Fallback to "dev-master"
                    final String version;
                    final String versionFromJson = composerJson.getString("version", null);
                    if (versionFromJson != null && !versionFromJson.trim().isEmpty()) {
                        version = versionFromJson.trim();
                    } else {
                        // Try to extract version from filename
                        version = extractVersionFromFilename(filename).orElse("dev-master");
                        EcsLogger.debug("com.auto1.pantera.composer")
                            .message("Version not found in composer.json, extracted from filename")
                            .eventCategory("repository")
                            .eventAction("archive_upload")
                            .field("package.version", version)
                            .field("file.name", filename)
                            .log();
                    }
                    
                    // Validate package name format (must be vendor/package)
                    final String[] parts = packageName.split("/");
                    if (parts.length != 2) {
                        EcsLogger.warn("com.auto1.pantera.composer")
                            .message("Invalid package name format, expected 'vendor/package'")
                            .eventCategory("repository")
                            .eventAction("archive_upload")
                            .eventOutcome("failure")
                            .field("package.name", packageName)
                            .log();
                        return CompletableFuture.completedFuture(
                            ResponseBuilder.badRequest()
                                .textBody("Package name must be in format 'vendor/package'")
                                .build()
                        );
                    }
                    
                    final String vendor = parts[0];
                    final String packagePart = parts[1];
                    
                    // Preserve original archive format extension
                    final String extension = isZip ? ".zip" : ".tar.gz";
                    
                    // Sanitize version for use in URLs and filenames
                    // Replace spaces and other invalid URL characters with hyphens
                    final String sanitizedVersion = sanitizeVersion(version);
                    
                    // For dev versions, preserve unique identifier from original filename to avoid overwrites
                    // Extract timestamp-hash pattern like "20220119164424-1e02e050" from filename
                    String uniqueSuffix = "";
                    if (sanitizedVersion.startsWith("dev-") || sanitizedVersion.contains("dev")) {
                        final java.util.regex.Pattern devPattern = java.util.regex.Pattern.compile(
                            "-(\\d{14}-[a-f0-9]{8,40})(?:\\.tar\\.gz|\\.tgz|\\.zip)$"
                        );
                        final java.util.regex.Matcher matcher = devPattern.matcher(filename);
                        if (matcher.find()) {
                            uniqueSuffix = "-" + matcher.group(1);
                            EcsLogger.debug("com.auto1.pantera.composer")
                                .message("Dev version detected, preserving unique identifier: " + uniqueSuffix)
                                .eventCategory("repository")
                                .eventAction("archive_upload")
                                .log();
                        }
                    }
                    
                    // Generate artifact filename: vendor-package-version[-unique].{zip|tar.gz}
                    final String artifactFilename = String.format(
                        "%s-%s-%s%s%s",
                        vendor,
                        packagePart,
                        sanitizedVersion,
                        uniqueSuffix,
                        extension
                    );
                    
                    // Store organized by vendor/package/version (like PyPI)
                    // Path: artifacts/vendor/package/version/vendor-package-version.{ext}
                    final String artifactPath = String.format(
                        "%s/%s/%s/%s",
                        vendor,
                        packagePart,
                        sanitizedVersion,
                        artifactFilename
                    );
                    
                    EcsLogger.info("com.auto1.pantera.composer")
                        .message("Processing Composer package upload")
                        .eventCategory("repository")
                        .eventAction("archive_upload")
                        .field("package.name", packageName)
                        .field("package.version", version)
                        .field("package.path", artifactPath)
                        .field("file.type", isZip ? "ZIP" : "TAR.GZ")
                        .log();
                    
                    // Create appropriate archive handler for final storage
                    // Use sanitized version for metadata consistency
                    final Archive archive = isZip
                        ? new Archive.Zip(new Archive.Name(artifactPath, sanitizedVersion))
                        : new TarArchive(new Archive.Name(artifactPath, sanitizedVersion));
                    
                    // Add archive to repository
                    CompletableFuture<Void> res = this.repository.addArchive(
                        archive,
                        new Content.From(bytes)
                    );
                    
                    // Record artifact event if enabled
                    if (this.events.isPresent()) {
                        res = res.thenAccept(
                            nothing -> {
                                final long size;
                                try {
                                    size = this.repository.storage()
                                        .metadata(archive.name().artifact())
                                        .thenApply(meta -> meta.read(Meta.OP_SIZE))
                                        .join()
                                        .map(Long::longValue)
                                        .orElse(0L);
                                } catch (final Exception e) {
                                    EcsLogger.warn("com.auto1.pantera.composer")
                                        .message("Failed to get file size for event")
                                        .eventCategory("repository")
                                        .eventAction("event_creation")
                                        .eventOutcome("failure")
                                        .field("error.message", e.getMessage())
                                        .log();
                                    return;
                                }
                                final long created = System.currentTimeMillis();
                                this.events.get().add(
                                    new ArtifactEvent(
                                        AddArchiveSlice.REPO_TYPE,
                                        this.rname,
                                        new Login(headers).getValue(),
                                        packageName,
                                        version,
                                        size,
                                        created,
                                        (Long) null  // No release date for local uploads
                                    )
                                );
                                EcsLogger.info("com.auto1.pantera.composer")
                                    .message("Recorded Composer package upload event")
                                    .eventCategory("repository")
                                    .eventAction("event_creation")
                                    .eventOutcome("success")
                                    .field("package.name", packageName)
                                    .field("package.version", version)
                                    .field("repository.name", this.rname)
                                    .field("package.size", size)
                                    .log();
                            }
                        );
                    }
                    
                    return res.thenApply(nothing -> ResponseBuilder.created().build());
                })
                .exceptionally(error -> {
                    EcsLogger.error("com.auto1.pantera.composer")
                        .message("Failed to process Composer package")
                        .eventCategory("repository")
                        .eventAction("archive_upload")
                        .eventOutcome("failure")
                        .error(error)
                        .field("file.name", filename)
                        .log();
                    return ResponseBuilder.internalError()
                        .textBody(
                            String.format(
                                "Failed to process package: %s",
                                error.getMessage()
                            )
                        )
                        .build();
                });
        });
    }
    
    /**
     * Extract version from filename.
     * Supports patterns like:
     * - package-1.0.0.zip -> 1.0.0
     * - vendor-package-2.5.1.tar.gz -> 2.5.1
     * - name-v1.2.3-beta.tgz -> v1.2.3-beta
     * 
     * @param filename Archive filename
     * @return Optional version string if found
     */
    private static Optional<String> extractVersionFromFilename(final String filename) {
        // Pattern to match semantic version in filename
        // Matches: major.minor.patch with optional pre-release/build metadata
        // Examples: 1.0.0, 2.5.1-beta, v3.0.0-rc.1, 1.2.3+20130313144700
        final Pattern pattern = Pattern.compile(
            "(v?\\d+\\.\\d+\\.\\d+(?:[-+][\\w\\.]+)?)"
        );
        final Matcher matcher = pattern.matcher(filename);
        
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }
    
    /**
     * Sanitize version string for use in URLs and file paths.
     * Replaces spaces and other invalid URL characters with plus signs.
     * Using + instead of - to avoid conflicts with existing hyphen usage in versions.
     * 
     * Examples:
     * - "1.406 62ee6db" -> "1.406+62ee6db"
     * - "2.0 beta" -> "2.0+beta"
     * - "1.0.0-beta" -> "1.0.0-beta" (hyphens preserved)
     * - "1.0.0" -> "1.0.0" (unchanged)
     * 
     * @param version Original version string
     * @return Sanitized version safe for URLs
     */
    private static String sanitizeVersion(final String version) {
        if (version == null || version.isEmpty()) {
            return version;
        }
        // Replace spaces with plus signs (URL-safe and avoids hyphen conflicts)
        // Also replace other problematic characters that might appear in versions
        return version
            .replaceAll("\\s+", "+")              // spaces -> plus signs
            .replaceAll("[^a-zA-Z0-9._+-]", "+"); // other invalid chars -> plus signs
    }
}
