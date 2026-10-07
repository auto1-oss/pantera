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
package com.auto1.pantera.settings.repo;

import java.time.Instant;
import javax.json.JsonObject;

/**
 * One row of the repository list: identity, the {@code repo} section of the
 * stored config and the audit columns a database keeps for it.
 * @param name Repository name
 * @param type Repository type, never blank ({@code unknown} when unreadable)
 * @param repo The {@code repo} section of the config, never null
 * @param updatedAt Last update time, null when unknown (YAML mode)
 * @param updatedBy Last updater, null when unknown
 * @param createdBy Creator, null when unknown
 * @since 2.2.10
 */
public record RepoSummary(
    String name, String type, JsonObject repo,
    Instant updatedAt, String updatedBy, String createdBy
) {
}
