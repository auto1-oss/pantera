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
package com.auto1.pantera.backfill;

/**
 * Parsed result of one Pantera repo YAML config file.
 *
 * @param repoName Repo name derived from the YAML filename stem (e.g. {@code go.yaml} → {@code go})
 * @param rawType Raw {@code repo.type} string from the YAML (e.g. {@code docker-proxy})
 * @since 1.20.13
 */
record RepoEntry(String repoName, String rawType) {
}
