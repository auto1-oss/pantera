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
 * Validates that repository name exists in storage.
 * @since 0.26
 */
public final class ExistenceVerifier implements Verifier {
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
    public ExistenceVerifier(final RepositoryName rname,
        final CrudRepoSettings crs) {
        this.rname = rname;
        this.crs = crs;
    }

    /**
     * Validate repository name exists.
     * @return True if exists
     */
    public boolean valid() {
        return this.crs.exists(this.rname);
    }

    /**
     * Get error message.
     * @return Error message
     */
    public String message() {
        return String.format("Repository %s does not exist. ", this.rname);
    }
}
