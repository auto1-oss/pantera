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

import com.auto1.pantera.group.GroupNegativeCacheKeys;
import com.auto1.pantera.http.cache.NegativeCacheKey;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Every negative-cache key the serving path could have written for one
 * request path: the group resolver's key for each group on the walk, and the
 * proxy slice's {@link NegativeCacheKey#fromPath} key for each leaf, each
 * computed from the path exactly as that slice sees it (after its own
 * prefix stripping).
 *
 * @since 2.2.9
 */
public final class DerivedNegativeKeys {

    /**
     * Topology.
     */
    private final RepoTopology topology;

    /**
     * Group key rule.
     */
    private final GroupNegativeCacheKeys groups;

    /**
     * Ctor.
     *
     * @param topology Repository topology
     */
    public DerivedNegativeKeys(final RepoTopology topology) {
        this.topology = topology;
        this.groups = new GroupNegativeCacheKeys();
    }

    /**
     * Keys for a request to a repository.
     *
     * @param repo Addressed repository
     * @param path Repository-relative decoded path
     * @return Keys with their producer, no duplicates, in walk order
     */
    public List<Derived> keys(final RepoTopology.RepoInfo repo, final String path) {
        final Set<NegativeCacheKey> seen = new LinkedHashSet<>();
        final List<Derived> out = new ArrayList<>();
        final List<RepoTopology.RepoInfo> walk = new ArrayList<>();
        walk.add(repo);
        walk.addAll(this.topology.reachable(repo.name()));
        for (final RepoTopology.RepoInfo info : walk) {
            if (info.group()) {
                this.groups.key(info.name(), info.type(), path).ifPresent(key -> {
                    if (seen.add(key)) {
                        out.add(new Derived(key, "group"));
                    }
                });
            } else {
                final NegativeCacheKey key = NegativeCacheKey.fromPath(
                    info.name(), info.type(), DerivedNegativeKeys.sliceView(info.type(), path)
                );
                if (seen.add(key)) {
                    out.add(new Derived(key, info.mode()));
                }
            }
        }
        return out;
    }

    /**
     * The path as a leaf slice sees it: pypi strips its {@code simple}
     * alias, composer its {@code direct-dists} alias (see
     * {@code RepositorySlices}).
     *
     * @param type Repository type
     * @param path Repository-relative path
     * @return Slice-visible path
     */
    private static String sliceView(final String type, final String path) {
        final String lower = type.toLowerCase(Locale.ROOT);
        final String alias;
        if (lower.startsWith("pypi")) {
            alias = "/simple";
        } else if (lower.startsWith("php")) {
            alias = "/direct-dists";
        } else {
            alias = null;
        }
        String result = path;
        if (alias != null) {
            if (path.equals(alias)) {
                result = "/";
            } else if (path.startsWith(alias + "/")) {
                result = path.substring(alias.length());
            }
        }
        return result;
    }

    /**
     * A derived key.
     *
     * @param key Key
     * @param producer Who writes it: {@code group}, {@code proxy} or {@code local}
     * @since 2.2.9
     */
    public record Derived(NegativeCacheKey key, String producer) {
    }
}
