/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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
    public boolean valid() {
        return !this.crs.hasSettingsDuplicates(this.rname);
    }

    /**
     * Get error message.
     * @return Error message
     */
    public String message() {
        return String.format("Repository %s has settings duplicates. Please remove repository and create it again.", this.rname);
    }
}
