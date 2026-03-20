/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cooldown;

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
