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
package com.auto1.pantera.audit;

import com.auto1.pantera.http.log.EcsLogger;

/**
 * Static helper class for structured artifact audit logging at INFO level.
 *
 * <p>All audit events are emitted to the {@code artifact.audit} logger and
 * routed through the dedicated log4j2 {@code AsyncConsole} appender.
 *
 * @since 1.22.0
 */
public final class AuditLogger {

    private static final String LOGGER = "artifact.audit";

    private AuditLogger() {
    }

    /**
     * Log a successful artifact upload.
     * @param repoType Repository type (e.g. "maven", "npm")
     * @param repoName Repository name
     * @param packageName Package/artifact name
     * @param version Version string
     * @param filename File name
     * @param size File size in bytes
     */
    public static void upload(final String repoType, final String repoName,
                              final String packageName, final String version,
                              final String filename, final long size) {
        EcsLogger.info(LOGGER)
            .message("Artifact uploaded")
            .field("event.category", "file")
            .field("event.action", "artifact_upload")
            .field("event.outcome", "success")
            .field("repository.type", repoType)
            .field("repository.name", repoName)
            .field("package.name", packageName)
            .field("package.version", version)
            .field("file.name", filename)
            .field("file.size", size)
            .log();
    }

    /**
     * Log a successful artifact download.
     * @param repoType Repository type (e.g. "maven", "npm")
     * @param repoName Repository name
     * @param packageName Package/artifact name
     * @param version Version string
     * @param filename File name
     * @param size File size in bytes
     */
    public static void download(final String repoType, final String repoName,
                                final String packageName, final String version,
                                final String filename, final long size) {
        EcsLogger.info(LOGGER)
            .message("Artifact downloaded")
            .field("event.category", "file")
            .field("event.action", "artifact_download")
            .field("event.outcome", "success")
            .field("repository.type", repoType)
            .field("repository.name", repoName)
            .field("package.name", packageName)
            .field("package.version", version)
            .field("file.name", filename)
            .field("file.size", size)
            .log();
    }

    /**
     * Log a successful artifact delete.
     * @param repoType Repository type (e.g. "maven", "npm")
     * @param repoName Repository name
     * @param packageName Package/artifact name
     * @param version Version string
     * @param filename File name
     */
    public static void delete(final String repoType, final String repoName,
                              final String packageName, final String version,
                              final String filename) {
        EcsLogger.info(LOGGER)
            .message("Artifact deleted")
            .field("event.category", "file")
            .field("event.action", "artifact_delete")
            .field("event.outcome", "success")
            .field("repository.type", repoType)
            .field("repository.name", repoName)
            .field("package.name", packageName)
            .field("package.version", version)
            .field("file.name", filename)
            .log();
    }

    /**
     * Log a successful artifact metadata resolution.
     * @param repoType Repository type (e.g. "maven", "npm")
     * @param repoName Repository name
     * @param packageName Package/artifact name
     */
    public static void resolution(final String repoType, final String repoName,
                                  final String packageName) {
        EcsLogger.info(LOGGER)
            .message("Artifact metadata resolved")
            .field("event.category", "file")
            .field("event.action", "artifact_resolution")
            .field("event.outcome", "success")
            .field("repository.type", repoType)
            .field("repository.name", repoName)
            .field("package.name", packageName)
            .log();
    }
}
