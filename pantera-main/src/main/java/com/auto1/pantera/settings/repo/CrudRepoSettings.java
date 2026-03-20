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

import com.auto1.pantera.api.RepositoryName;
import java.util.Collection;
import javax.json.JsonStructure;

/**
 * Create/Read/Update/Delete repository settings.
 * @since 0.26
 */
public interface CrudRepoSettings {

    /**
     * List all existing repositories.
     * @return List of the repositories
     */
    Collection<String> listAll();

    /**
     * List user's repositories.
     * @param uname User id (name)
     * @return List of the repositories
     */
    Collection<String> list(String uname);

    /**
     * Checks if repository settings exists by repository name.
     * @param rname Repository name
     * @return True if found
     */
    boolean exists(RepositoryName rname);

    /**
     * Get repository settings as json.
     * @param name Repository name.
     * @return Json repository settings
     */
    JsonStructure value(RepositoryName name);

    /**
     * Add new repository.
     * @param rname Repository name.
     * @param value New repository settings
     */
    void save(RepositoryName rname, JsonStructure value);

    /**
     * Add new repository with actor tracking.
     * @param rname Repository name
     * @param value New repository settings
     * @param actor Username performing the action
     */
    default void save(RepositoryName rname, JsonStructure value, String actor) {
        save(rname, value);
    }

    /**
     * Remove repository.
     * @param rname Repository name
     */
    void delete(RepositoryName rname);

    /**
     * Move repository and all data.
     * @param rname Old repository name
     * @param newrname New repository name
     */
    void move(RepositoryName rname, RepositoryName newrname);

    /**
     * Checks that stored repository has duplicates of settings names.
     * @param rname Repository name
     * @return True if has duplicates
     */
    boolean hasSettingsDuplicates(RepositoryName rname);
}
