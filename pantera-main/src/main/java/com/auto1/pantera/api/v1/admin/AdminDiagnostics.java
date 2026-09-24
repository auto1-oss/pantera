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

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * What the cache tools need from the serving side: the repository topology,
 * in-process repository requests, breaker state, and this node's identity.
 *
 * @param topology Repository topology
 * @param fetch In-process repository fetch
 * @param breakers Breaker state
 * @param node This node's identity (hostname), reported on every response
 * @since 2.2.9
 */
public record AdminDiagnostics(
    RepoTopology topology, RepoFetch fetch, BreakerProbe breakers, String node
) {

    /**
     * Diagnostics with no serving-side access (tests, DB-less boots): no
     * repositories, no in-process fetch, no breaker state.
     */
    public AdminDiagnostics() {
        this(RepoTopology.EMPTY, RepoFetch.UNAVAILABLE, BreakerProbe.NONE, hostname());
    }

    /**
     * This node's hostname: {@code HOSTNAME} (set in containers), else the
     * resolved local host name. Resolve once at boot — it may do DNS.
     *
     * @return Hostname, {@code unknown} when unresolvable
     */
    private static String hostname() {
        final String env = System.getenv("HOSTNAME");
        String name;
        if (env != null && !env.isBlank()) {
            name = env;
        } else {
            try {
                name = InetAddress.getLocalHost().getHostName();
            } catch (final UnknownHostException ex) {
                name = "unknown";
            }
        }
        return name;
    }
}
