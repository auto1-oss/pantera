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
package com.auto1.pantera.group;

import com.auto1.pantera.http.cache.NegativeCacheKey;
import java.util.Optional;

/**
 * The negative-cache key {@link GroupResolver} uses for a request path —
 * built by the resolver's own rules (artifact name from
 * {@link ArtifactNameParser}, per-file version from
 * {@code GroupResolver#negativeCacheVersion}) so admin tooling can never
 * drift from what the serving path writes.
 *
 * @since 2.2.9
 */
public final class GroupNegativeCacheKeys {

    /**
     * Key for a group request.
     *
     * @param group Group repository name
     * @param groupType Group type, e.g. {@code npm-group}
     * @param path Group-relative decoded request path
     * @return Key, empty when the path names no artifact (the resolver then
     *  fans out without consulting the negative cache)
     */
    public Optional<NegativeCacheKey> key(
        final String group, final String groupType, final String path
    ) {
        return ArtifactNameParser.parse(groupType, path).map(
            name -> new NegativeCacheKey(
                group, groupType, name,
                GroupResolver.negativeCacheVersion(
                    NegativeCacheKey.fromPath(group, groupType, path).artifactVersion(),
                    path
                )
            )
        );
    }
}
