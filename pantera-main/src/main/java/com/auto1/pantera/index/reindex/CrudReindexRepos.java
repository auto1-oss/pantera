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
package com.auto1.pantera.index.reindex;

import com.auto1.pantera.api.RepositoryName;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.settings.repo.CrudRepoSettings;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Function;
import javax.json.JsonObject;
import javax.json.JsonValue;

/**
 * {@link ReindexRepos} over the repository settings: the type comes from
 * the stored repository config, the storage root from the repository's
 * resolved storage when it lives on the local file system.
 *
 * @since 2.2.9
 */
public final class CrudReindexRepos implements ReindexRepos {

    /**
     * Repository settings.
     */
    private final CrudRepoSettings settings;

    /**
     * Resolved storage of a repository, alias-aware.
     */
    private final Function<String, Optional<Storage>> storages;

    /**
     * Ctor.
     * @param settings Repository settings
     * @param storages Resolved storage of a repository
     */
    public CrudReindexRepos(
        final CrudRepoSettings settings,
        final Function<String, Optional<Storage>> storages
    ) {
        this.settings = settings;
        this.storages = storages;
    }

    @Override
    public Collection<String> names() {
        return this.settings.listAll();
    }

    @Override
    public boolean exists(final String name) {
        return this.settings.exists(new RepositoryName.Simple(name));
    }

    @Override
    public Target target(final String name) {
        final String type = this.type(name);
        final Target res;
        if (type == null || type.isBlank()) {
            res = new Target(null, "repository config has no type");
        } else if (type.endsWith("-group")) {
            res = new Target(type, "group repositories have no storage of their own");
        } else {
            final Optional<Storage> storage = this.storages.apply(name);
            if (storage.isEmpty()) {
                res = new Target(type, "repository has no storage configured");
            } else {
                final Optional<Path> root = storage.get().pathFor(Key.ROOT);
                if (root.isEmpty()) {
                    res = new Target(
                        type, "storage is not on the local file system (e.g. S3)"
                    );
                } else {
                    res = new Target(type, root.get());
                }
            }
        }
        return res;
    }

    /**
     * Repository type from the stored config, whether or not it is wrapped
     * in a {@code repo} object.
     * @param name Repository name
     * @return Type, null if absent
     */
    private String type(final String name) {
        final JsonObject value = this.settings.value(new RepositoryName.Simple(name))
            .asJsonObject();
        final JsonObject repo;
        if (value.containsKey("repo")
            && value.get("repo").getValueType() == JsonValue.ValueType.OBJECT) {
            repo = value.getJsonObject("repo");
        } else {
            repo = value;
        }
        final String type;
        if (repo.containsKey("type")
            && repo.get("type").getValueType() == JsonValue.ValueType.STRING) {
            type = repo.getString("type");
        } else {
            type = null;
        }
        return type;
    }
}
