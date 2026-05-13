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
package com.auto1.pantera.api.verifier;

import com.auto1.pantera.api.RepositoryName;
import com.auto1.pantera.settings.repo.CrudRepoSettings;

/**
 * Validates that repository name has duplicates of settings names.
 * @since 0.26
 */
public final class SettingsDuplicatesVerifier implements Verifier {
    /**
     * Repository name.
     */
    private final RepositoryName rname;

    /**
     * Repository settings CRUD.
     */
    private final CrudRepoSettings crs;

    /**
     * Ctor.
     * @param rname Repository name
     * @param crs Repository settings CRUD
     */
    public SettingsDuplicatesVerifier(final RepositoryName rname,
        final CrudRepoSettings crs) {
        this.rname = rname;
        this.crs = crs;
    }

    /**
     * Validate repository name has duplicates of settings names.
     * @return True if has no duplicates
     */
    @Override
    public boolean valid() {
        return !this.crs.hasSettingsDuplicates(this.rname);
    }

    /**
     * Get error message.
     * @return Error message
     */
    @Override
    public String message() {
        return String.format("Repository %s has settings duplicates. Please remove repository and create it again.", this.rname);
    }
}
