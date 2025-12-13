/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown;

import com.artipie.cooldown.CooldownReason;
import java.time.Instant;
import java.util.Optional;

final class DbBlockRecord {

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

    long id() {
        return this.id;
    }

    String repoType() {
        return this.repoType;
    }

    String repoName() {
        return this.repoName;
    }

    String artifact() {
        return this.artifact;
    }

    String version() {
        return this.version;
    }

    CooldownReason reason() {
        return this.reason;
    }

    BlockStatus status() {
        return this.status;
    }

    String blockedBy() {
        return this.blockedBy;
    }

    Instant blockedAt() {
        return this.blockedAt;
    }

    Instant blockedUntil() {
        return this.blockedUntil;
    }

    Optional<Instant> unblockedAt() {
        return this.unblockedAt;
    }

    Optional<String> unblockedBy() {
        return this.unblockedBy;
    }

    Optional<String> installedBy() {
        return this.installedBy;
    }
}
