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
package com.auto1.pantera.repojob;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.cache.StoragesCache;
import com.auto1.pantera.misc.Json2Yaml;
import java.util.function.Function;
import javax.json.JsonObject;

/**
 * The raw storage of a resolved storage config, as repository jobs and the
 * orphan scan address it: by repository key at its top level.
 *
 * @since 2.2.14
 */
public final class RepoStorages implements Function<JsonObject, Storage> {

    /**
     * Storages cache.
     */
    private final StoragesCache cache;

    /**
     * Ctor.
     * @param cache Storages cache
     */
    public RepoStorages(final StoragesCache cache) {
        this.cache = cache;
    }

    @Override
    public Storage apply(final JsonObject config) {
        if ("s3-express".equals(config.getString("type", ""))) {
            // Directory buckets list neither in key order nor from a start
            // key, so a purge could not resume or prove it is complete.
            throw new IllegalStateException(
                "Purging an S3 Express directory bucket is not supported: empty the directory"
                    + " with a bucket lifecycle rule, then retry the job"
            );
        }
        return this.cache.storage(new Json2Yaml().apply(config.toString()));
    }
}
