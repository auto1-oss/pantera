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

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Candidate package names for the inspector's suggestion search
 * (blocking; call off the event loop).
 *
 * @since 2.2.9
 */
public interface SuggestLookup {

    /**
     * Lookup without a database: nothing to suggest.
     */
    SuggestLookup NONE = new SuggestLookup() {
        @Override
        public List<SuggestRow> rows(
            final Collection<String> families, final SuggestQuery query, final int limit
        ) {
            return List.of();
        }

        @Override
        public Map<String, String> mavenPaths(final Collection<String> names) {
            return Map.of();
        }
    };

    /**
     * Index and cooldown rows whose stored name matches every word of the
     * query through one of its {@link SuggestQuery#patterns()}, closest
     * names first.
     *
     * @param families Format families to include; empty for all
     * @param query Query
     * @param limit Maximum rows per source
     * @return Rows
     */
    List<SuggestRow> rows(Collection<String> families, SuggestQuery query, int limit);

    /**
     * Index path of maven-layout names stored in the dotted form.
     *
     * @param names Dotted names
     * @return Name to a path prefix, for the names the index knows
     */
    Map<String, String> mavenPaths(Collection<String> names);
}
