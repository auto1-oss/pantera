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
     *
     * <p>Repo-level index queries (no specific package) are suppressed; this
     * method is a no-op when {@code packageName} is null or empty. Callers
     * should pass the resolved package name explicitly because the MDC slot
     * for {@code package.name} is often empty by the time a metadata-render
     * pipeline emits this event (the MDC is detached from the HTTP request
     * scope inside RxJava chains).
     *
     * @param packageName Resolved package name — required
     */
    public static void resolution(final String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        final EcsLogger logger = EcsLogger.info(LOGGER)
            .message("Artifact metadata resolved")
            .eventCategory("file")
            .eventAction("artifact_resolution")
            .eventOutcome("success")
            .field("package.name", packageName);
        mdcField(logger, "repository.type", EcsMdc.REPO_TYPE);
        mdcField(logger, "repository.name", EcsMdc.REPO_NAME);
        mdcField(logger, "package.version", EcsMdc.PACKAGE_VERSION);
        logger.log();
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
     * @param checksum SHA-256 hex digest, or {@code null} when unavailable
     */
    public static void publish(final String repoName, final String repoType,
        final String artifactName, final String version,
        final double size, final String owner, final Long releaseDate,
        final String checksum) {
        final EcsLogger logger = EcsLogger.info(LOGGER)
            .message(releaseDate != null
                ? String.format("Artifact publish recorded (release=%d)", releaseDate)
                : "Artifact publish recorded")
            .eventCategory("database")
            .eventAction("artifact_publish")
            .eventOutcome("success")
            .field("repository.type", repoType)
            .field("repository.name", repoName)
            .field("package.name", artifactName)
            .field("package.version", version)
            .field("package.size", size)
            .field("user.name", owner);
        if (checksum != null && !checksum.isEmpty()) {
            logger.field("package.checksum", checksum);
        }
        logger.log();
    }

    private static EcsLogger emit(final String message, final String action) {
        final EcsLogger logger = EcsLogger.info(LOGGER)
            .message(message)
            .eventCategory("file")
            .eventAction(action)
            .eventOutcome("success");
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
