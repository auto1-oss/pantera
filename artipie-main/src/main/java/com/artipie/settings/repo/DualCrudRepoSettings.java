/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.settings.repo;

import com.artipie.api.RepositoryName;
import com.artipie.http.log.EcsLogger;
import java.util.Collection;
import javax.json.JsonStructure;

/**
 * Composite CrudRepoSettings that writes to both DB (primary) and
 * YAML storage (secondary). Reads come from the primary.
 * The secondary write ensures MapRepositories picks up the config
 * from YAML files so the upload/download path can resolve repos.
 * @since 1.21.0
 */
public final class DualCrudRepoSettings implements CrudRepoSettings {

    private final CrudRepoSettings primary;
    private final CrudRepoSettings secondary;

    /**
     * Ctor.
     * @param primary Primary (DB) repo settings
     * @param secondary Secondary (YAML) repo settings
     */
    public DualCrudRepoSettings(
        final CrudRepoSettings primary, final CrudRepoSettings secondary
    ) {
        this.primary = primary;
        this.secondary = secondary;
    }

    @Override
    public Collection<String> listAll() {
        return this.primary.listAll();
    }

    @Override
    public Collection<String> list(final String uname) {
        return this.primary.list(uname);
    }

    @Override
    public boolean exists(final RepositoryName rname) {
        return this.primary.exists(rname);
    }

    @Override
    public JsonStructure value(final RepositoryName name) {
        return this.primary.value(name);
    }

    @Override
    public void save(final RepositoryName rname, final JsonStructure value) {
        this.save(rname, value, null);
    }

    @Override
    public void save(final RepositoryName rname, final JsonStructure value,
        final String actor) {
        this.primary.save(rname, value, actor);
        try {
            this.secondary.save(rname, value);
        } catch (final Exception ex) {
            EcsLogger.warn("com.artipie.settings.repo")
                .message("Failed to save repo config to secondary (YAML)")
                .field("repository.name", rname.toString())
                .error(ex)
                .log();
        }
    }

    @Override
    public void delete(final RepositoryName rname) {
        this.primary.delete(rname);
        try {
            this.secondary.delete(rname);
        } catch (final Exception ex) {
            EcsLogger.warn("com.artipie.settings.repo")
                .message("Failed to delete repo config from secondary (YAML)")
                .field("repository.name", rname.toString())
                .error(ex)
                .log();
        }
    }

    @Override
    public void move(final RepositoryName rname, final RepositoryName newrname) {
        this.primary.move(rname, newrname);
        try {
            this.secondary.move(rname, newrname);
        } catch (final Exception ex) {
            EcsLogger.warn("com.artipie.settings.repo")
                .message("Failed to move repo config in secondary (YAML)")
                .field("repository.name", rname.toString())
                .error(ex)
                .log();
        }
    }

    @Override
    public boolean hasSettingsDuplicates(final RepositoryName rname) {
        return this.primary.hasSettingsDuplicates(rname);
    }
}
