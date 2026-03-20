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
package com.auto1.pantera.cooldown;

import com.auto1.pantera.cooldown.CooldownReason;
import java.time.Instant;
import java.util.Optional;

public final class DbBlockRecord {

    private final long id;
    private final String repoType;
    private final String repoName;
    private final String artifact;
    private final String version;
    private final CooldownReason reason;
    private final BlockStatus status;
    private final String blockedBy;
    private final Instant blockedAt;
    private final Instant blockedUntil;
    private final Optional<Instant> unblockedAt;
    private final Optional<String> unblockedBy;
    private final Optional<String> installedBy;

    DbBlockRecord(
        final long id,
        final String repoType,
        final String repoName,
        final String artifact,
        final String version,
        final CooldownReason reason,
        final BlockStatus status,
        final String blockedBy,
        final Instant blockedAt,
        final Instant blockedUntil,
        final Optional<Instant> unblockedAt,
        final Optional<String> unblockedBy,
        final Optional<String> installedBy
    ) {
        this.id = id;
        this.repoType = repoType;
        this.repoName = repoName;
        this.artifact = artifact;
        this.version = version;
        this.reason = reason;
        this.status = status;
        this.blockedBy = blockedBy;
        this.blockedAt = blockedAt;
        this.blockedUntil = blockedUntil;
        this.unblockedAt = unblockedAt;
        this.unblockedBy = unblockedBy;
        this.installedBy = installedBy;
    }

    public long id() {
        return this.id;
    }

    public String repoType() {
        return this.repoType;
    }

    public String repoName() {
        return this.repoName;
    }

    public String artifact() {
        return this.artifact;
    }

    public String version() {
        return this.version;
    }

    public CooldownReason reason() {
        return this.reason;
    }

    public BlockStatus status() {
        return this.status;
    }

    public String blockedBy() {
        return this.blockedBy;
    }

    public Instant blockedAt() {
        return this.blockedAt;
    }

    public Instant blockedUntil() {
        return this.blockedUntil;
    }

    public Optional<Instant> unblockedAt() {
        return this.unblockedAt;
    }

    public Optional<String> unblockedBy() {
        return this.unblockedBy;
    }

    public Optional<String> installedBy() {
        return this.installedBy;
    }
}
