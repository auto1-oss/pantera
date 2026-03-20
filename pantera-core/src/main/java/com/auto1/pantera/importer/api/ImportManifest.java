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
package com.auto1.pantera.importer.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable manifest describing a single artifact upload.
 *
 * <p>The manifest is sent from the CLI to the server via HTTP headers to preserve
 * canonical metadata captured from the export dump.</p>
 *
 * @since 1.0
 */
public final class ImportManifest {

    /**
     * Repository name.
     */
    private final String repo;

    /**
     * Repository type.
     */
    private final String repoType;

    /**
     * Relative artifact storage path.
     */
    private final String path;

    /**
     * Artifact logical name.
     */
    private final String artifact;

    /**
     * Artifact version or coordinate.
     */
    private final String version;

    /**
     * Artifact size in bytes.
     */
    private final long size;

    /**
     * Artifact owner.
     */
    private final String owner;

    /**
     * Artifact created timestamp epoch millis.
     */
    private final long created;

    /**
     * Artifact release timestamp epoch millis.
     */
    private final Long release;

    /**
     * SHA-1 checksum.
     */
    private final String sha1;

    /**
     * SHA-256 checksum.
     */
    private final String sha256;

    /**
     * MD5 checksum.
     */
    private final String md5;

    /**
     * Construct manifest.
     *
     * @param repo Repository
     * @param repoType Repository type
     * @param path Storage path
     * @param artifact Artifact name
     * @param version Artifact version
     * @param size Size in bytes
     * @param owner Owner
     * @param created Created epoch millis
     * @param release Release epoch millis, nullable
     * @param sha1 SHA-1 checksum, nullable
     * @param sha256 SHA-256 checksum, nullable
     * @param md5 MD5 checksum, nullable
     */
    public ImportManifest(
        final String repo,
        final String repoType,
        final String path,
        final String artifact,
        final String version,
        final long size,
        final String owner,
        final long created,
        final Long release,
        final String sha1,
        final String sha256,
        final String md5
    ) {
        this.repo = Objects.requireNonNull(repo, "repo");
        this.repoType = Objects.requireNonNull(repoType, "repoType");
        this.path = Objects.requireNonNull(path, "path");
        this.artifact = artifact;
        this.version = version;
        this.size = size;
        this.owner = owner;
        this.created = created;
        this.release = release;
        this.sha1 = sha1;
        this.sha256 = sha256;
        this.md5 = md5;
    }

    public String repo() {
        return this.repo;
    }

    public String repoType() {
        return this.repoType;
    }

    public String path() {
        return this.path;
    }

    public Optional<String> artifact() {
        return Optional.ofNullable(this.artifact);
    }

    public Optional<String> version() {
        return Optional.ofNullable(this.version);
    }

    public long size() {
        return this.size;
    }

    public Optional<String> owner() {
        return Optional.ofNullable(this.owner);
    }

    public long created() {
        return this.created;
    }

    public Optional<Long> release() {
        return Optional.ofNullable(this.release);
    }

    public Optional<String> sha1() {
        return Optional.ofNullable(this.sha1);
    }

    public Optional<String> sha256() {
        return Optional.ofNullable(this.sha256);
    }

    public Optional<String> md5() {
        return Optional.ofNullable(this.md5);
    }
}
