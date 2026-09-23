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
package com.auto1.pantera.api.v1;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.api.RepositoryName;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.cache.StoragesCache;
import com.auto1.pantera.misc.Json2Yaml;
import com.auto1.pantera.settings.DbStorageByAlias;
import com.auto1.pantera.settings.repo.CrudRepoSettings;
import com.auto1.pantera.settings.repo.RepoConfig;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javax.json.Json;
import javax.json.JsonObject;

/**
 * Repo-scoped storage of a DB-configured repository, resolved exactly as
 * {@code DbRepositories} does for the serving path: the stored config goes
 * through {@link RepoConfig#from} with the repository's storage aliases
 * (global + per-repo), so a storage given as a string alias
 * ({@code storage: "default"}) resolves like an inline storage mapping.
 * The result is the same {@code SubStorage(repo, storage)} view the format
 * adapter receives. Blocking (DB reads) — call off the event loop only.
 *
 * @since 2.2.9
 */
final class DbRepoStorage implements Function<String, Optional<Storage>> {

    /**
     * Repository settings (DB).
     */
    private final CrudRepoSettings repos;

    /**
     * Storage alias records ({@code name}, {@code config}) visible to a
     * repository, global first then per-repo.
     */
    private final Function<String, List<JsonObject>> aliases;

    /**
     * Storages cache.
     */
    private final StoragesCache cache;

    /**
     * Ctor.
     * @param repos Repository settings (DB)
     * @param aliases Alias records visible to a repository, by repository name
     * @param cache Storages cache
     */
    DbRepoStorage(
        final CrudRepoSettings repos,
        final Function<String, List<JsonObject>> aliases,
        final StoragesCache cache
    ) {
        this.repos = repos;
        this.aliases = aliases;
        this.cache = cache;
    }

    @Override
    public Optional<Storage> apply(final String repo) {
        final RepositoryName rname = new RepositoryName.Simple(repo);
        final Optional<Storage> result;
        if (this.repos.exists(rname)) {
            final JsonObject value = this.repos.value(rname).asJsonObject();
            final JsonObject wrapped;
            if (value.containsKey("repo")) {
                wrapped = value;
            } else {
                wrapped = Json.createObjectBuilder().add("repo", value).build();
            }
            final YamlMapping yaml = new Json2Yaml().apply(wrapped.toString());
            result = RepoConfig.from(
                yaml,
                DbStorageByAlias.from(this.aliases.apply(repo)),
                new Key.From(repo),
                this.cache,
                false
            ).storageOpt();
        } else {
            result = Optional.empty();
        }
        return result;
    }
}
