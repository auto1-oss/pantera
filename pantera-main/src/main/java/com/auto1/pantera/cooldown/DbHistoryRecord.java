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

import com.auto1.pantera.cooldown.api.CooldownReason;
import java.time.Instant;
import java.util.Optional;

/**
 * Row of the {@code artifact_cooldowns_history} table.
 *
 * @param id The row id in the history table.
 * @param originalId The id the record had in {@code artifact_cooldowns}.
 * @param repoType Repository type.
 * @param repoName Repository name.
 * @param artifact Artifact name.
 * @param version Artifact version.
 * @param reason Reason the block was created.
 * @param blockedBy Actor that created the block.
 * @param blockedAt Instant the block was created.
 * @param blockedUntil Instant the block was scheduled to expire.
 * @param installedBy Optional actor that initiated install.
 * @param archivedAt Instant the row was archived.
 * @param archiveReason Reason the block was archived.
 * @param archivedBy Actor that archived the row.
 * @checkstyle ParameterNumberCheck (5 lines)
 */
public record DbHistoryRecord(
    long id,
    long originalId,
    String repoType,
    String repoName,
    String artifact,
    String version,
    CooldownReason reason,
    String blockedBy,
    Instant blockedAt,
    Instant blockedUntil,
    Optional<String> installedBy,
    Instant archivedAt,
    ArchiveReason archiveReason,
    String archivedBy
) {
}
