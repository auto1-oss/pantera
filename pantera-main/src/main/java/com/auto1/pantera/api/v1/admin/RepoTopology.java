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

import com.auto1.pantera.http.client.RemoteConfig;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.settings.repo.Repositories;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only view of the configured repositories for the cache tools:
 * names, types, group members and proxy remotes.
 *
 * @since 2.2.9
 */
public interface RepoTopology {

    /**
     * Topology with no repositories (DB-less tests, disabled diagnostics).
     */
    RepoTopology EMPTY = new RepoTopology() {
        @Override
        public Optional<RepoInfo> repo(final String name) {
            return Optional.empty();
        }

        @Override
        public List<RepoInfo> all() {
            return List.of();
        }
    };

    /**
     * Repository by name.
     *
     * @param name Repository name
     * @return Repository, empty when not configured
     */
    Optional<RepoInfo> repo(String name);

    /**
     * Every configured repository.
     *
     * @return Repositories
     */
    List<RepoInfo> all();

    /**
     * Leaf (non-group) repositories a group reaches, in walk order, nested
     * groups expanded depth-first. A non-group repository is its own only
     * leaf.
     *
     * @param name Repository name
     * @return Leaf repositories, empty when unknown
     */
    default List<RepoInfo> leaves(final String name) {
        final Set<String> seen = new LinkedHashSet<>();
        final List<RepoInfo> out = new ArrayList<>();
        this.collect(name, seen, out, true);
        return out;
    }

    /**
     * Every repository a group reaches (nested groups included, the group
     * itself excluded), in walk order.
     *
     * @param name Repository name
     * @return Reachable repositories
     */
    default List<RepoInfo> reachable(final String name) {
        final Set<String> seen = new LinkedHashSet<>();
        final List<RepoInfo> out = new ArrayList<>();
        this.collect(name, seen, out, false);
        return out.stream().filter(info -> !info.name().equals(name))
            .collect(Collectors.toList());
    }

    /**
     * Groups that (transitively) contain a repository.
     *
     * @param name Repository name
     * @return Group names
     */
    default Set<String> groupsContaining(final String name) {
        final Set<String> out = new LinkedHashSet<>();
        for (final RepoInfo info : this.all()) {
            if (info.group() && this.reachable(info.name()).stream()
                .anyMatch(member -> member.name().equals(name))) {
                out.add(info.name());
            }
        }
        return out;
    }

    /**
     * Depth-first walk.
     *
     * @param name Current repository
     * @param seen Visited names (cycle guard)
     * @param out Output
     * @param leavesOnly Whether only leaves are collected
     */
    private void collect(
        final String name, final Set<String> seen, final List<RepoInfo> out,
        final boolean leavesOnly
    ) {
        if (!seen.add(name)) {
            return;
        }
        final Optional<RepoInfo> info = this.repo(name);
        if (info.isEmpty()) {
            return;
        }
        if (!leavesOnly || !info.get().group()) {
            out.add(info.get());
        }
        if (info.get().group()) {
            for (final String member : info.get().members()) {
                this.collect(member, seen, out, leavesOnly);
            }
        }
    }

    /**
     * One repository.
     *
     * @param name Name
     * @param type Type, e.g. {@code npm-proxy}
     * @param members Direct group members in declared order (empty unless a group)
     * @param remotes Upstream URLs (empty unless a proxy)
     * @since 2.2.9
     */
    record RepoInfo(String name, String type, List<String> members, List<String> remotes) {

        /**
         * Ctor.
         *
         * @param name Name
         * @param type Type
         * @param members Members
         * @param remotes Remotes
         */
        public RepoInfo(
            final String name, final String type, final List<String> members,
            final List<String> remotes
        ) {
            this.name = name;
            this.type = type == null ? "" : type;
            this.members = List.copyOf(members);
            this.remotes = List.copyOf(remotes);
        }

        /**
         * Repository mode.
         *
         * @return {@code group}, {@code proxy} or {@code local}
         */
        public String mode() {
            final String mode;
            if (this.group()) {
                mode = "group";
            } else if (this.type.endsWith("-proxy")) {
                mode = "proxy";
            } else {
                mode = "local";
            }
            return mode;
        }

        /**
         * Whether this is a group.
         *
         * @return True for {@code *-group}
         */
        public boolean group() {
            return this.type.endsWith("-group");
        }

        /**
         * Format family: the type without its mode suffix, lower case.
         *
         * @return Family, e.g. {@code npm}
         */
        public String family() {
            return this.type.toLowerCase(Locale.ROOT).replaceAll("-(group|proxy|local|remote)$", "");
        }
    }

    /**
     * Topology backed by the live repository registry.
     *
     * @since 2.2.9
     */
    final class FromRepositories implements RepoTopology {

        /**
         * Repository registry.
         */
        private final Repositories repos;

        /**
         * Ctor.
         *
         * @param repos Repository registry
         */
        public FromRepositories(final Repositories repos) {
            this.repos = repos;
        }

        @Override
        public Optional<RepoInfo> repo(final String name) {
            return this.repos.config(name).map(FromRepositories::info);
        }

        @Override
        public List<RepoInfo> all() {
            final Collection<RepoConfig> configs = this.repos.configs();
            return configs.stream().map(FromRepositories::info).collect(Collectors.toList());
        }

        /**
         * Convert a repository config.
         *
         * @param cfg Config
         * @return Info
         */
        private static RepoInfo info(final RepoConfig cfg) {
            List<String> remotes;
            try {
                remotes = cfg.remotes().stream()
                    .map(RemoteConfig::uri)
                    .map(Object::toString)
                    .collect(Collectors.toList());
            } catch (final IllegalStateException ex) {
                remotes = List.of();
            }
            List<String> members;
            try {
                members = cfg.members();
            } catch (final IllegalStateException ex) {
                members = List.of();
            }
            return new RepoInfo(cfg.name(), cfg.type(), members, remotes);
        }
    }
}
