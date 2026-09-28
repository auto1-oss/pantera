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
package com.auto1.pantera.composer.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.composer.Repository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.scheduling.ArtifactEvent;
import java.util.Locale;
import java.util.function.Function;
import javax.json.JsonObject;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slice for adding a package to the repository in ZIP format.
 * Accepts any .zip file and extracts metadata from composer.json inside.
 * See <a href="https://getcomposer.org/doc/05-repositories.md#artifact">Artifact repository</a>.
 *
 * <p><b>Trace context contract.</b> Trace context (trace.id / span.id /
 * span.parent.id) is inherited from the {@code EcsLoggingSlice} MDC scope
 * set at request entry. Any async hop introduced in this slice MUST use
 * {@code ContextualExecutor.contextualize(...)} (or an equivalent MDC
 * capture-and-restore) to preserve trace.id across the executor
 * boundary — without it, log lines emitted from the worker thread
 * surface in Kibana with no trace correlation back to the originating
 * request.
 */
final class AddArchiveSlice implements Slice {
    /**
     * Repository type.
     */
    public static final String REPO_TYPE = "php";

    /**
     * Unique build identifier of a dev archive file name
     * (e.g. {@code -20220119164424-1e02e050.zip}).
     */
    private static final Pattern DEV_SUFFIX = Pattern.compile(
        "-(\\d{14}-[a-f0-9]{8,40})(?:\\.tar\\.gz|\\.tgz|\\.zip)$"
    );

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
        this(repository, Optional.empty(), rname,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /**
     * Legacy ctor (no synchronous index writer).
     * @param repository Repository
     * @param events Artifact events
     * @param rname Repository name
     */
    AddArchiveSlice(
        final Repository repository, final Optional<Queue<ArtifactEvent>> events,
        final String rname
    ) {
        this(repository, events, rname,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /** Synchronous artifact-index writer for read-after-write consistency. */
    private final com.auto1.pantera.index.SyncArtifactIndexer syncIndex;

    /**
     * Ctor with synchronous index writer.
     * @param repository Repository
     * @param events Artifact events
     * @param rname Repository name
     * @param syncIndex Synchronous artifact-index writer
     */
    AddArchiveSlice(
        final Repository repository, final Optional<Queue<ArtifactEvent>> events,
        final String rname,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex
    ) {
        this.repository = repository;
        this.events = events;
        this.rname = rname;
        this.syncIndex = syncIndex;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final String uri = line.uri().getPath();
        
        // Validate path doesn't contain directory traversal
        if (uri.contains("..")) {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Rejected archive path with directory traversal")
                .eventCategory("web")
                .eventAction("archive_upload")
                .eventOutcome("failure")
                .field("url.path", uri)
                .field("log.source", "application")
                .log();
            return ResponseBuilder.badRequest()
                .textBody("Path traversal not allowed")
                .completedFuture();
        }

        // Validate archive format - support .zip, .tar.gz, .tgz
        final String lowerUri = uri.toLowerCase(Locale.ROOT);
        final boolean isZip = lowerUri.endsWith(".zip");
        final boolean isTarGz = lowerUri.endsWith(".tar.gz") || lowerUri.endsWith(".tgz");

        if (!isZip && !isTarGz) {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Rejected unsupported archive format")
                .eventCategory("web")
                .eventAction("archive_upload")
                .eventOutcome("failure")
                .field("url.path", uri)
                .field("log.source", "application")
                .log();
            return ResponseBuilder.badRequest()
                .textBody("Only .zip, .tar.gz, and .tgz archives are supported for Composer packages")
                .completedFuture();
        }
        
        // Extract the filename from the URI for initial storage
        final String filename = uri.substring(uri.lastIndexOf('/') + 1);
        final Upload upload = new Upload(uri, filename, isZip, headers);

        // First, extract composer.json to get the real package metadata
        return body.asBytesFuture().thenCompose(bytes -> {
            // Choose appropriate archive handler based on format
            final Archive tempArchive = isZip
                ? new Archive.Zip(new Archive.Name(filename, "unknown"))
                : new TarArchive(new Archive.Name(filename, "unknown"));
            return tempArchive.composerFrom(new Content.From(bytes))
                .handle((composerJson, error) -> {
                    if (error != null) {
                        // Not an archive, no composer.json, or composer.json
                        // is not a JSON object: the client sent a bad package.
                        EcsLogger.warn("com.auto1.pantera.composer")
                            .message("Rejected unreadable Composer archive")
                            .eventCategory("web")
                            .eventAction("archive_upload")
                            .eventOutcome("failure")
                            .error(error)
                            .field("file.name", filename)
                            .field("log.source", "application")
                            .log();
                        return CompletableFuture.completedFuture(
                            ResponseBuilder.badRequest()
                                .textBody(
                                    "The archive could not be read or has no valid composer.json"
                                )
                                .build()
                        );
                    }
                    return this.upload(composerJson, bytes, upload);
                })
                .thenCompose(Function.identity());
        });
    }

    /**
     * Validate the package identity from composer.json, check the release
     * against what is already published, and store it.
     *
     * @param composerJson Parsed composer.json
     * @param bytes Uploaded archive bytes
     * @param upload Upload request details
     * @return Response
     */
    private CompletableFuture<Response> upload(
        final JsonObject composerJson, final byte[] bytes, final Upload upload
    ) {
        final String packageName;
        final String versionFromJson;
        try {
            packageName = composerJson.getString("name", null);
            versionFromJson = composerJson.getString("version", null);
        } catch (final ClassCastException ex) {
            return ResponseBuilder.badRequest()
                .textBody("composer.json 'name' and 'version' must be strings")
                .completedFuture();
        }
        if (packageName == null || packageName.trim().isEmpty()) {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Missing or empty 'name' in composer.json")
                .eventCategory("web")
                .eventAction("archive_upload")
                .eventOutcome("failure")
                .field("url.path", upload.uri())
                .field("log.source", "application")
                .log();
            return ResponseBuilder.badRequest()
                .textBody("composer.json must contain non-empty 'name' field")
                .completedFuture();
        }
        // Handle version - try multiple sources in priority order:
        // 1. composer.json version field
        // 2. Extract from filename (e.g., package-1.0.0.tar.gz)
        // 3. Fallback to "dev-master"
        final String version;
        if (versionFromJson != null && !versionFromJson.trim().isEmpty()) {
            version = versionFromJson.trim();
        } else {
            version = extractVersionFromFilename(upload.filename()).orElse("dev-master");
            EcsLogger.debug("com.auto1.pantera.composer")
                .message("Version not found in composer.json, extracted from filename")
                .eventCategory("web")
                .eventAction("archive_upload")
                .field("package.version", version)
                .field("file.name", upload.filename())
                .field("log.source", "application")
                .log();
        }
        // Validate package name format (must be vendor/package)
        final String[] parts = packageName.split("/");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Invalid package name format, expected 'vendor/package'")
                .eventCategory("web")
                .eventAction("archive_upload")
                .eventOutcome("failure")
                .field("package.name", packageName)
                .field("log.source", "application")
                .log();
            return ResponseBuilder.badRequest()
                .textBody("Package name must be in format 'vendor/package'")
                .completedFuture();
        }
        final Archive archive = this.archive(parts[0], parts[1], version, upload);
        final String sanitizedVersion = archive.name().version();
        return new ReleaseGuard(this.repository).check(
            archive.name().artifact(), packageName, sanitizedVersion, upload.zip(), bytes
        ).thenCompose(verdict -> {
            if (verdict == ReleaseGuard.Verdict.CONFLICT) {
                EcsLogger.warn("com.auto1.pantera.composer")
                    .message("Rejected re-upload of a published release with different content")
                    .eventCategory("web")
                    .eventAction("archive_upload")
                    .eventOutcome("failure")
                    .field("event.reason", "version_exists")
                    .field("package.name", packageName)
                    .field("package.version", sanitizedVersion)
                    .field("repository.name", this.rname)
                    .field("log.source", "application")
                    .log();
                return ResponseBuilder.from(RsStatus.CONFLICT)
                    .textBody(
                        String.format(
                            "%s %s is already published with different content;"
                                + " publish a new version instead",
                            packageName, sanitizedVersion
                        )
                    )
                    .completedFuture();
            }
            if (verdict == ReleaseGuard.Verdict.IDENTICAL) {
                return ResponseBuilder.created().completedFuture();
            }
            return this.store(archive, bytes, packageName, version, upload);
        }).exceptionally(error -> {
            EcsLogger.error("com.auto1.pantera.composer")
                .message("Failed to process Composer package")
                .eventCategory("web")
                .eventAction("archive_upload")
                .eventOutcome("failure")
                .error(error)
                .field("file.name", upload.filename())
                .field("log.source", "application")
                .log();
            return ResponseBuilder.internalError()
                .textBody("Failed to store the package")
                .build();
        });
    }

    /**
     * Build the archive handle for final storage:
     * {@code vendor/package/version/vendor-package-version[-unique].{zip|tar.gz}}.
     *
     * @param vendor Vendor
     * @param packagePart Package
     * @param version Resolved version
     * @param upload Upload request details
     * @return Archive whose name carries the storage path and sanitised version
     */
    private Archive archive(
        final String vendor, final String packagePart, final String version, final Upload upload
    ) {
        // Preserve original archive format extension
        final String extension = upload.zip() ? ".zip" : ".tar.gz";
        // Sanitize version for use in URLs and filenames
        // Replace spaces and other invalid URL characters with hyphens
        final String sanitizedVersion = sanitizeVersion(version);
        // For dev versions, preserve unique identifier from original filename to avoid overwrites
        // Extract timestamp-hash pattern like "20220119164424-1e02e050" from filename
        String uniqueSuffix = "";
        if (sanitizedVersion.startsWith("dev-") || sanitizedVersion.contains("dev")) {
            final Matcher matcher = DEV_SUFFIX.matcher(upload.filename());
            if (matcher.find()) {
                uniqueSuffix = "-" + matcher.group(1);
                EcsLogger.debug("com.auto1.pantera.composer")
                    .message("Dev version detected, preserving unique identifier: " + uniqueSuffix)
                    .eventCategory("web")
                    .eventAction("archive_upload")
                    .field("log.source", "application")
                    .log();
            }
        }
        // Generate artifact filename: vendor-package-version[-unique].{zip|tar.gz}
        final String artifactFilename = String.format(
            "%s-%s-%s%s%s", vendor, packagePart, sanitizedVersion, uniqueSuffix, extension
        );
        // Store organized by vendor/package/version (like PyPI)
        // Path: artifacts/vendor/package/version/vendor-package-version.{ext}
        final String artifactPath = String.format(
            "%s/%s/%s/%s", vendor, packagePart, sanitizedVersion, artifactFilename
        );
        EcsLogger.info("com.auto1.pantera.composer")
            .message("Processing Composer package upload")
            .eventCategory("web")
            .eventAction("archive_upload")
            .field("package.name", vendor + "/" + packagePart)
            .field("package.version", version)
            .field("package.path", artifactPath)
            .field("file.type", upload.zip() ? "ZIP" : "TAR.GZ")
            .field("log.source", "application")
            .log();
        // Use sanitized version for metadata consistency
        return upload.zip()
            ? new Archive.Zip(new Archive.Name(artifactPath, sanitizedVersion))
            : new TarArchive(new Archive.Name(artifactPath, sanitizedVersion));
    }

    /**
     * Store the archive, record the artifact event and update the index
     * synchronously so the group resolver sees the new artifact at once.
     *
     * @param archive Archive handle
     * @param bytes Archive bytes
     * @param packageName Package name
     * @param version Resolved version
     * @param upload Upload request details
     * @return 201 once stored
     */
    private CompletableFuture<Response> store(
        final Archive archive,
        final byte[] bytes,
        final String packageName,
        final String version,
        final Upload upload
    ) {
        return this.repository.addArchive(archive, new Content.From(bytes))
            .thenCompose(nothing -> this.repository.storage()
                .metadata(archive.name().artifact())
                .<Long>thenApply(meta -> meta.read(Meta.OP_SIZE).map(Long::longValue).orElse(0L))
                .exceptionally(error -> {
                    EcsLogger.warn("com.auto1.pantera.composer")
                        .message("Failed to get file size for event")
                        .eventCategory("web")
                        .eventAction("event_creation")
                        .eventOutcome("failure")
                        .error(error)
                        .field("log.source", "application")
                        .log();
                    return 0L;
                })
            )
            .thenCompose(size -> {
                final ArtifactEvent event = new ArtifactEvent(
                    AddArchiveSlice.REPO_TYPE,
                    this.rname,
                    new Login(upload.headers()).getValue(),
                    packageName,
                    version,
                    size,
                    System.currentTimeMillis(),
                    null,  // No release date for local uploads
                    archive.name().artifact().string()
                ).withRequestContext(upload.headers());
                this.events.ifPresent(queue -> queue.add(event));
                EcsLogger.info("com.auto1.pantera.composer")
                    .message("Recorded Composer package upload event")
                    .eventCategory("web")
                    .eventAction("event_creation")
                    .eventOutcome("success")
                    .field("package.name", packageName)
                    .field("package.version", version)
                    .field("repository.name", this.rname)
                    .field("package.size", size)
                    .field("log.source", "application")
                    .log();
                return this.syncIndex.recordSync(event);
            })
            .thenApply(nothing -> ResponseBuilder.created().build());
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

    /**
     * Details of one upload request.
     *
     * @param uri Request path
     * @param filename Uploaded file name
     * @param zip True for ZIP, false for TAR.GZ
     * @param headers Request headers
     */
    private record Upload(String uri, String filename, boolean zip, Headers headers) {
    }
}
