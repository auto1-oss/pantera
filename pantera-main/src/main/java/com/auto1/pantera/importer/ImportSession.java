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
package com.auto1.pantera.importer;

import com.auto1.pantera.importer.api.ChecksumPolicy;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistent import session record.
 *
 * @since 1.0
 */
public final class ImportSession {

    private final long id;
    private final String key;
    private final ImportSessionStatus status;
    private final String repo;
    private final String repoType;
    private final String path;
    private final String artifact;
    private final String version;
    private final Long size;
    private final String sha1;
    private final String sha256;
    private final String md5;
    private final ChecksumPolicy policy;
    private final int attempts;

    ImportSession(
        final long id,
        final String key,
        final ImportSessionStatus status,
        final String repo,
        final String repoType,
        final String path,
        final String artifact,
        final String version,
        final Long size,
        final String sha1,
        final String sha256,
        final String md5,
        final ChecksumPolicy policy,
        final int attempts
    ) {
        this.id = id;
        this.key = Objects.requireNonNull(key);
        this.status = status;
        this.repo = repo;
        this.repoType = repoType;
        this.path = path;
        this.artifact = artifact;
        this.version = version;
        this.size = size;
        this.sha1 = sha1;
        this.sha256 = sha256;
        this.md5 = md5;
        this.policy = policy;
        this.attempts = attempts;
    }

    long id() {
        return this.id;
    }

    String key() {
        return this.key;
    }

    ImportSessionStatus status() {
        return this.status;
    }

    String repo() {
        return this.repo;
    }

    String repoType() {
        return this.repoType;
    }

    String path() {
        return this.path;
    }

    Optional<String> artifact() {
        return Optional.ofNullable(this.artifact);
    }

    Optional<String> version() {
        return Optional.ofNullable(this.version);
    }

    Optional<Long> size() {
        return Optional.ofNullable(this.size);
    }

    Optional<String> sha1() {
        return Optional.ofNullable(this.sha1);
    }

    Optional<String> sha256() {
        return Optional.ofNullable(this.sha256);
    }

    Optional<String> md5() {
        return Optional.ofNullable(this.md5);
    }

    ChecksumPolicy policy() {
        return this.policy;
    }

    int attempts() {
        return this.attempts;
    }

    /**
     * Create transient session for environments without persistence.
     *
     * @param request Import request
     * @return Session
     */
    static ImportSession transientSession(final ImportRequest request) {
        return new ImportSession(
            -1L,
            request.idempotency(),
            ImportSessionStatus.IN_PROGRESS,
            request.repo(),
            request.repoType(),
            request.path(),
            request.artifact().orElse(null),
            request.version().orElse(null),
            request.size().orElse(null),
            request.sha1().orElse(null),
            request.sha256().orElse(null),
            request.md5().orElse(null),
            request.policy(),
            1
        );
    }
}
