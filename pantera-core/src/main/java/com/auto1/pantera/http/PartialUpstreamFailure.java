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
package com.auto1.pantera.http;

/**
 * A 502 of a proxy repository with several remotes of which only some
 * failed.
 *
 * <p>A proxy asks all its remotes for a file. When one of them fails and
 * another one answers "not found", the proxy cannot say the file is absent
 * and answers 502. That 502 is no failure of the repository: a remote of it
 * answered, so the repository still serves everything that remote has. A
 * group must therefore not count it against the member's circuit breaker,
 * or one unreachable remote takes every other remote of the repository out
 * of the group with it. The 502 itself stands: nobody knows whether the
 * failed remote has the file.</p>
 *
 * @since 2.2.14
 */
public final class PartialUpstreamFailure {

    /**
     * Header marking the 502 of a proxy of which at least one remote
     * answered.
     */
    public static final String HEADER = "X-Pantera-Partial-Upstream-Failure";

    /**
     * Answer of a repository.
     */
    private final Response response;

    /**
     * Ctor.
     * @param response Answer of a repository
     */
    public PartialUpstreamFailure(final Response response) {
        this.response = response;
    }

    /**
     * Whether only some remotes of the repository failed.
     * @return True for a 5xx that carries the marker
     */
    public boolean present() {
        return this.response.status().serverError()
            && !this.response.headers().values(PartialUpstreamFailure.HEADER).isEmpty();
    }
}
