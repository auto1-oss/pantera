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
package com.auto1.pantera.scheduling;

import com.auto1.pantera.http.log.EcsMdc;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.MDC;

/**
 * Artifact data record.
 */
public final class ArtifactEvent {

    /**
     * Default value for owner when owner is not found or irrelevant.
     */
    public static final String DEF_OWNER = "UNKNOWN";

    /**
     * Repository type.
     */
    private final String repoType;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Owner username.
     */
    private final String owner;

    /**
     * Event type.
     */
    private final Type eventType;

    /**
     * Artifact name.
     */
    private final String artifactName;

    /**
     * Artifact version.
     */
    private final String version;

    /**
     * Package size.
     */
    private final long size;

    /**
     * Artifact uploaded time.
     */
    private final long created;

    /**
     * Remote artifact release time, when known (primarily for proxies).
     */
    private final Optional<Long> release;

    /**
     * Path prefix for index-based group lookups (e.g., "com/google/guava/guava/32.1.3-jre").
     * Nullable — only set by proxy package processors.
     */
    private final String pathPrefix;

    /**
     * Originating HTTP trace.id — captured from MDC at construction time so
     * audit logs emitted later (on the DB-consumer thread) can be correlated
     * back to the HTTP request that produced the event. Nullable.
     */
    private final String traceId;

    /**
     * Artifact SHA-256 digest (hex) — populated when known (maven UploadSlice
     * runs {@code generateChecksums} after the upload completes and attaches
     * the digest via {@link #withChecksum(String)}). Nullable.
     */
    private final String checksum;

    /**
     * Ctor for the event to remove all artifact versions.
     * @param repoType Repository type
     * @param repoName Repository name
     * @param artifactName Artifact name
     */
    public ArtifactEvent(String repoType, String repoName, String artifactName) {
        this(repoType, repoName, ArtifactEvent.DEF_OWNER, artifactName, "", 0L, 0L, Optional.empty(), null, Type.DELETE_ALL);
    }

    /**
     * Ctor for the event to remove artifact with specified version.
     * @param repoType Repository type
     * @param repoName Repository name
     * @param artifactName Artifact name
     * @param version Artifact version
     */
    public ArtifactEvent(String repoType, String repoName,
                         String artifactName, String version) {
        this(repoType, repoName, ArtifactEvent.DEF_OWNER, artifactName, version, 0L, 0L, Optional.empty(), null, Type.DELETE_VERSION);
    }

    /**
     * @param repoType Repository type
     * @param repoName Repository name
     * @param owner Owner username
     * @param artifactName Artifact name
     * @param version Artifact version
     * @param size Artifact size
     * @param created Artifact created date
     * @param release Remote release date
     * @param pathPrefix Path prefix for index lookups (nullable)
     * @param etype Event type
     */
    private ArtifactEvent(String repoType, String repoName, String owner,
                          String artifactName, String version, long size,
                          long created, Optional<Long> release, String pathPrefix,
                          Type etype) {
        this(repoType, repoName, owner, artifactName, version, size, created,
            release, pathPrefix, etype, MDC.get(EcsMdc.TRACE_ID), null);
    }

    /**
     * Full-args ctor used by the public {@link #withChecksum(String)} copy path
     * and by the auto-trace-capture delegate above. The {@code traceId} is
     * captured once at event-construction time; later copies preserve it.
     */
    private ArtifactEvent(String repoType, String repoName, String owner,
                          String artifactName, String version, long size,
                          long created, Optional<Long> release, String pathPrefix,
                          Type etype, String traceId, String checksum) {
        this.repoType = repoType;
        this.repoName = repoName;
        this.owner = owner;
        this.artifactName = artifactName;
        this.version = version;
        this.size = size;
        this.created = created;
        this.release = release == null ? Optional.empty() : release;
        this.pathPrefix = pathPrefix;
        this.eventType = etype;
        this.traceId = traceId;
        this.checksum = checksum;
    }

    /**
     * Return a copy of this event with the provided SHA-256 checksum attached.
     * Used by upload paths that compute the digest asynchronously after
     * construction (e.g. maven {@code UploadSlice.generateChecksums}).
     *
     * @param sha256Hex SHA-256 digest as lowercase hex, or {@code null} to clear
     * @return Copy with the checksum set
     */
    public ArtifactEvent withChecksum(final String sha256Hex) {
        return new ArtifactEvent(
            this.repoType, this.repoName, this.owner, this.artifactName,
            this.version, this.size, this.created, this.release,
            this.pathPrefix, this.eventType, this.traceId, sha256Hex
        );
    }

    /**
     * Originating HTTP {@code trace.id} captured at construction time.
     * @return Trace id, or {@code null} when the event was produced outside
     *     a request scope (e.g. scheduled jobs, import CLI).
     */
    public String traceId() {
        return this.traceId;
    }

    /**
     * Artifact SHA-256 digest (hex).
     * @return Checksum, or {@code null} when not yet computed.
     */
    public String checksum() {
        return this.checksum;
    }

    /**
     * @param repoType Repository type
     * @param repoName Repository name
     * @param owner Owner username
     * @param artifactName Artifact name
     * @param version Artifact version
     * @param size Artifact size
     * @param created Artifact created date
     * @param etype Event type
     */
    public ArtifactEvent(final String repoType, final String repoName, final String owner,
                         final String artifactName, final String version, final long size,
                         final long created) {
        this(repoType, repoName, owner, artifactName, version, size, created, Optional.empty(), null, Type.INSERT);
    }

    /**
     * Backward compatible constructor with explicit event type.
     */
    public ArtifactEvent(final String repoType, final String repoName, final String owner,
                         final String artifactName, final String version, final long size,
                         final long created, final Type etype) {
        this(repoType, repoName, owner, artifactName, version, size, created, Optional.empty(), null, etype);
    }

    /**
     * Ctor to insert artifact data with explicit created and release timestamps.
     * @param repoType Repository type
     * @param repoName Repository name
     * @param owner Owner username
     * @param artifactName Artifact name
     * @param version Artifact version
     * @param size Artifact size
     * @param created Artifact created (uploaded) date
     * @param release Remote release date (nullable)
     */
    public ArtifactEvent(final String repoType, final String repoName, final String owner,
                         final String artifactName, final String version, final long size,
                         final long created, final Long release) {
        this(repoType, repoName, owner, artifactName, version, size, created, Optional.ofNullable(release), null, Type.INSERT);
    }

    /**
     * Ctor to insert artifact data with explicit created and release timestamps and path prefix.
     * @param repoType Repository type
     * @param repoName Repository name
     * @param owner Owner username
     * @param artifactName Artifact name
     * @param version Artifact version
     * @param size Artifact size
     * @param created Artifact created (uploaded) date
     * @param release Remote release date (nullable)
     * @param pathPrefix Path prefix for index lookups (nullable)
     */
    public ArtifactEvent(final String repoType, final String repoName, final String owner,
                         final String artifactName, final String version, final long size,
                         final long created, final Long release, final String pathPrefix) {
        this(repoType, repoName, owner, artifactName, version, size, created, Optional.ofNullable(release), pathPrefix, Type.INSERT);
    }

    /**
     * Ctor to insert artifact data with creation time {@link System#currentTimeMillis()}.
     * @param repoType Repository type
     * @param repoName Repository name
     * @param owner Owner username
     * @param artifactName Artifact name
     * @param version Artifact version
     * @param size Artifact size
     */
    public ArtifactEvent(final String repoType, final String repoName, final String owner,
                         final String artifactName, final String version, final long size) {
        this(repoType, repoName, owner, artifactName, version, size,
            System.currentTimeMillis(), Optional.empty(), null, Type.INSERT);
    }

    /**
     * Repository identification.
     * @return Repo info
     */
    public String repoType() {
        return this.repoType;
    }

    /**
     * Repository identification.
     * @return Repo info
     */
    public String repoName() {
        return this.repoName;
    }

    /**
     * Artifact identifier.
     * @return Repo id
     */
    public String artifactName() {
        return this.artifactName;
    }

    /**
     * Artifact identifier.
     * @return Repo id
     */
    public String artifactVersion() {
        return this.version;
    }

    /**
     * Package size.
     * @return Size of the package
     */
    public long size() {
        return this.size;
    }

    /**
     * Artifact uploaded time.
     * @return Created datetime
     */
    public long createdDate() {
        return this.created;
    }

    /**
     * Remote artifact release time, when known.
     * @return Optional release datetime
     */
    public Optional<Long> releaseDate() {
        return this.release;
    }

    /**
     * Owner username.
     * @return Username
     */
    public String owner() {
        return this.owner;
    }

    /**
     * Path prefix for index-based group lookups.
     * @return Path prefix or null if not set
     */
    public String pathPrefix() {
        return this.pathPrefix;
    }

    /**
     * Event type.
     * @return The type of event
     */
    public Type eventType() {
        return this.eventType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.repoName, this.artifactName, this.version, this.eventType);
    }

    @Override
    public boolean equals(final Object other) {
        final boolean res;
        if (this == other) {
            res = true;
        } else if (other == null || getClass() != other.getClass()) {
            res = false;
        } else {
            final ArtifactEvent that = (ArtifactEvent) other;
            res = that.repoName.equals(this.repoName) && that.artifactName.equals(this.artifactName)
                && that.version.equals(this.version) && that.eventType.equals(this.eventType);
        }
        return res;
    }

    @Override
    public String toString() {
        return "ArtifactEvent{" +
            "repoType='" + repoType + '\'' +
            ", repoName='" + repoName + '\'' +
            ", owner='" + owner + '\'' +
            ", eventType=" + eventType +
            ", artifactName='" + artifactName + '\'' +
            ", version='" + version + '\'' +
            ", size=" + size +
            ", created=" + created +
            ", release=" + release.orElse(null) +
            '}';
    }

    /**
     * Events type.
     * @since 1.3
     */
    public enum Type {
        /**
         * Add artifact data.
         */
        INSERT,

        /**
         * Remove artifact data by version.
         */
        DELETE_VERSION,

        /**
         * Remove artifact data by artifact name (all versions).
         */
        DELETE_ALL
    }

}
