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
package com.auto1.pantera.auth.signin;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Where the lines of the sign-in failures report are kept.
 *
 * @since 2.2.15
 */
public interface SignInFailureStore {

    /**
     * Add events to their lines: a line that exists gets the counts added
     * and the texts of the later event, a new one is created.
     *
     * @param events Events, at most one per line
     */
    void save(Collection<SignInFailure> events);

    /**
     * Lines of one kind, the most recent first.
     *
     * @param query What to list
     * @return The page and the number of lines that match
     */
    Page list(Query query);

    /**
     * Remove the lines whose last event is older than a time.
     *
     * @param before Lines last seen before this are removed
     * @return Number of lines removed
     */
    int purge(Instant before);

    /**
     * What to list.
     *
     * @param kind Kind of the lines
     * @param user User name to look for, empty for all
     * @param exact Whether the user name must be equal (true) or may be a
     *  part of the name (false)
     * @param clientIp Part of the client address to look for, empty for all
     * @param limit Most lines to return
     * @param offset Lines to skip
     * @since 2.2.15
     */
    record Query(String kind, String user, boolean exact, String clientIp, int limit, int offset) {

        /**
         * Whether a line matches.
         *
         * @param line The line
         * @return True when it does
         */
        boolean matches(final SignInFailure line) {
            final String name = this.user.strip().toLowerCase(java.util.Locale.ROOT);
            final boolean named;
            if (name.isEmpty()) {
                named = true;
            } else if (this.exact) {
                named = line.username().equals(name);
            } else {
                named = line.username().contains(name);
            }
            return line.kind().equals(this.kind) && named
                && line.clientIp().contains(this.clientIp.strip());
        }
    }

    /**
     * A page of lines.
     *
     * @param items The lines
     * @param total Number of lines that match the query
     * @since 2.2.15
     */
    record Page(List<SignInFailure> items, int total) {
    }
}
