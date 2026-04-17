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
import java.util.Objects;

/**
 * Input describing an artifact download request subject to cooldown checks.
 */
public final class CooldownRequest {

    private final String repoType;
    private final String repoName;
    private final String artifact;
    private final String version;
    private final String requestedBy;
    private final Instant requestedAt;

    public CooldownRequest(
        final String repoType,
        final String repoName,
        final String artifact,
        final String version,
        final String requestedBy,
        final Instant requestedAt
    ) {
        this.repoType = Objects.requireNonNull(repoType);
        this.repoName = Objects.requireNonNull(repoName);
        this.artifact = Objects.requireNonNull(artifact);
        this.version = Objects.requireNonNull(version);
        this.requestedBy = Objects.requireNonNull(requestedBy);
        this.requestedAt = Objects.requireNonNull(requestedAt);
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

    public String requestedBy() {
        return this.requestedBy;
    }

    public Instant requestedAt() {
        return this.requestedAt;
    }
}
