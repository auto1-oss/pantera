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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory topology for tests.
 *
 * @since 2.2.9
 */
final class FakeTopology implements RepoTopology {

    /**
     * Repositories by name.
     */
    private final Map<String, RepoInfo> repos = new LinkedHashMap<>();

    /**
     * Add a proxy.
     *
     * @param name Name
     * @param type Type
     * @return This
     */
    FakeTopology proxy(final String name, final String type) {
        this.repos.put(name, new RepoInfo(name, type, List.of(), List.of("https://upstream.example")));
        return this;
    }

    /**
     * Add a local repository.
     *
     * @param name Name
     * @param type Type
     * @return This
     */
    FakeTopology local(final String name, final String type) {
        this.repos.put(name, new RepoInfo(name, type, List.of(), List.of()));
        return this;
    }

    /**
     * Add a group.
     *
     * @param name Name
     * @param type Type
     * @param members Members
     * @return This
     */
    FakeTopology group(final String name, final String type, final String... members) {
        this.repos.put(name, new RepoInfo(name, type, List.of(members), List.of()));
        return this;
    }

    @Override
    public Optional<RepoInfo> repo(final String name) {
        return Optional.ofNullable(this.repos.get(name));
    }

    @Override
    public List<RepoInfo> all() {
        return new ArrayList<>(this.repos.values());
    }
}
