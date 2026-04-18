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
package com.auto1.pantera.cooldown.api;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Information about an active cooldown block.
 */
public final class CooldownBlock {

    private final String repoType;
    private final String repoName;
    private final String artifact;
    private final String version;
    private final CooldownReason reason;
    private final Instant blockedAt;
    private final Instant blockedUntil;
    private final List<CooldownDependency> dependencies;

    public CooldownBlock(
        final String repoType,
        final String repoName,
        final String artifact,
        final String version,
        final CooldownReason reason,
        final Instant blockedAt,
        final Instant blockedUntil,
        final List<CooldownDependency> dependencies
    ) {
        this.repoType = Objects.requireNonNull(repoType);
        this.repoName = Objects.requireNonNull(repoName);
        this.artifact = Objects.requireNonNull(artifact);
        this.version = Objects.requireNonNull(version);
        this.reason = Objects.requireNonNull(reason);
        this.blockedAt = Objects.requireNonNull(blockedAt);
        this.blockedUntil = Objects.requireNonNull(blockedUntil);
        this.dependencies = List.copyOf(Objects.requireNonNull(dependencies));
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

    public Instant blockedAt() {
        return this.blockedAt;
    }

    public Instant blockedUntil() {
        return this.blockedUntil;
    }

    public List<CooldownDependency> dependencies() {
        return Collections.unmodifiableList(this.dependencies);
    }
}
