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
package com.auto1.pantera.api.v1.admin;

import com.auto1.pantera.cooldown.CooldownPackageRow;
import java.util.Collection;
import java.util.List;

/**
 * Cooldown records of a package (blocking; call off the event loop).
 *
 * @since 2.2.9
 */
@FunctionalInterface
public interface CooldownLookup {

    /**
     * Lookup without a database: no records.
     */
    CooldownLookup NONE = (repos, names) -> List.of();

    /**
     * Records of a package.
     *
     * @param repos Repository names
     * @param names Artifact name spellings
     * @return Live and archived records
     */
    List<CooldownPackageRow> find(Collection<String> repos, Collection<String> names);
}
