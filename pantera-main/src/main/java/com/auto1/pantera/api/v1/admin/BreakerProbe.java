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

import java.util.List;

/**
 * Read-only view of the two circuit breakers for a repository: the
 * group-member breaker (keyed by member name) and the upstream HTTP breaker
 * of each remote (keyed by {@code scheme://host:port}). Never creates
 * breaker state.
 *
 * @since 2.2.9
 */
public interface BreakerProbe {

    /**
     * Probe that knows no breaker state.
     */
    BreakerProbe NONE = new BreakerProbe() {
        @Override
        public String memberStatus(final String repo) {
            return "unknown";
        }

        @Override
        public List<Upstream> upstreams(final String repo) {
            return List.of();
        }
    };

    /**
     * Group-member breaker status of a repository.
     *
     * @param repo Repository name
     * @return {@code online}, {@code blocked}, {@code probing} or
     *  {@code unknown}
     */
    String memberStatus(String repo);

    /**
     * Upstream HTTP breakers of a proxy's remotes that have state.
     *
     * @param repo Repository name
     * @return Breakers
     */
    List<Upstream> upstreams(String repo);

    /**
     * Upstream breaker state.
     *
     * @param key Breaker key ({@code scheme://host:port})
     * @param open Whether it is open (fast-failing)
     * @param retryAfterSeconds Remaining open time, 0 when closed
     * @since 2.2.9
     */
    record Upstream(String key, boolean open, long retryAfterSeconds) {
    }
}
