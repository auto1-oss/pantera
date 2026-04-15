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

import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.EcsLogger;
import org.slf4j.MDC;

/**
 * Static helper class for structured artifact audit logging at INFO level.
 *
 * <p>All audit events are emitted to the {@code artifact.audit} logger and
 * routed through the dedicated log4j2 {@code AsyncConsole} appender.
 *
 * <p>Repository type and name are read from MDC if available (set by
 * {@code EcsLoggingSlice} earlier in the request chain). Explicit
 * parameters override the MDC values when non-empty.
 *
 * @since 1.22.0
 */
public final class AuditLogger {

    private static final String LOGGER = "artifact.audit";

    private AuditLogger() {
    }

    /**
     * Log a successful artifact upload.
     * @param filename File name
     * @param size File size in bytes
     */
    public static void upload(final String filename, final long size) {
        emit("Artifact uploaded", "artifact_upload")
            .field("file.name", filename)
            .field("file.size", size)
            .log();
    }

    /**
     * Log a successful artifact download.
     * @param filename File name
     * @param size File size in bytes
     */
    public static void download(final String filename, final long size) {
        emit("Artifact downloaded", "artifact_download")
            .field("file.name", filename)
            .field("file.size", size)
            .log();
    }

    /**
     * Log a successful artifact delete.
     * @param filename File name
     */
    public static void delete(final String filename) {
        emit("Artifact deleted", "artifact_delete")
            .field("file.name", filename)
            .log();
    }

    /**
     * Log a successful artifact metadata resolution.
     */
    public static void resolution() {
        emit("Artifact metadata resolved", "artifact_resolution")
            .log();
    }

    /**
     * Log a successful artifact publish (DB index record written).
     * @param repoName Repository name
     * @param repoType Repository type
     * @param artifactName Artifact/package name
     * @param version Artifact version
     * @param size File size in bytes
     * @param owner Owner/uploader name
     * @param releaseDate Release timestamp epoch-millis, or {@code null} if absent
     */
    public static void publish(final String repoName, final String repoType,
        final String artifactName, final String version,
        final double size, final String owner, final Long releaseDate) {
        final EcsLogger logger = EcsLogger.info(LOGGER)
            .message(releaseDate != null
                ? String.format("Artifact publish recorded (release=%d)", releaseDate)
                : "Artifact publish recorded")
            .field("event.category", "database")
            .field("event.action", "artifact_publish")
            .field("event.outcome", "success")
            .field("repository.type", repoType)
            .field("repository.name", repoName)
            .field("package.name", artifactName)
            .field("package.version", version)
            .field("package.size", size)
            .field("user.name", owner);
        logger.log();
    }

    private static EcsLogger emit(final String message, final String action) {
        final EcsLogger logger = EcsLogger.info(LOGGER)
            .message(message)
            .field("event.category", "file")
            .field("event.action", action)
            .field("event.outcome", "success");
        mdcField(logger, "repository.type", EcsMdc.REPO_TYPE);
        mdcField(logger, "repository.name", EcsMdc.REPO_NAME);
        mdcField(logger, "package.name", EcsMdc.PACKAGE_NAME);
        mdcField(logger, "package.version", EcsMdc.PACKAGE_VERSION);
        return logger;
    }

    private static void mdcField(final EcsLogger logger,
        final String fieldName, final String mdcKey) {
        final String val = MDC.get(mdcKey);
        if (val != null && !val.isEmpty()) {
            logger.field(fieldName, val);
        }
    }
}
