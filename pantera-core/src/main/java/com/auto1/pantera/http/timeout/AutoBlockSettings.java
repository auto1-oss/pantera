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
package com.auto1.pantera.http.timeout;

import java.time.Duration;

/**
 * Configuration for auto-block behavior. All values configurable via YAML.
 *
 * @since 1.20.13
 */
public record AutoBlockSettings(
    int failureThreshold,
    Duration initialBlockDuration,
    Duration maxBlockDuration
) {

    public static AutoBlockSettings defaults() {
        return new AutoBlockSettings(3, Duration.ofSeconds(40), Duration.ofMinutes(5));
    }
}
