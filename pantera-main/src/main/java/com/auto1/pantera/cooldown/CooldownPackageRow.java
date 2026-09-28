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

import java.time.Instant;

/**
 * One cooldown record of a package version, live or archived, as the admin
 * package inspector reads it.
 *
 * @param repoType Repository type the block was recorded under
 * @param repoName Repository the block was recorded under
 * @param artifact Artifact name as stored
 * @param version Version
 * @param state {@code blocked}, {@code released} or {@code expired}
 * @param blockedUntil End of the cooldown window
 * @param reason Block reason
 * @param live Whether the record is a live row (else an archived one)
 * @param at When the row was released or archived; null for an active block
 * @since 2.2.9
 */
public record CooldownPackageRow(
    String repoType, String repoName, String artifact, String version,
    String state, Instant blockedUntil, String reason, boolean live, Instant at
) {
}
