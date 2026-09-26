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
package com.auto1.pantera.cooldown.response;

import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.http.Response;

/**
 * Factory for building cooldown HTTP responses per repository type.
 *
 * @since 2.2.0
 */
public interface CooldownResponseFactory {

    /**
     * Header every cooldown verdict carries, whatever its status (a 403 for a
     * blocked artifact, a format-specific 404/403 when every version of a
     * package is blocked). Its values are {@code blocked} and
     * {@code all-blocked}. Wrapping slices and group walks key on it to treat
     * the response as Pantera's own authoritative answer: relayed verbatim,
     * never laundered into a non-authoritative miss, never negative-cached.
     */
    String HEADER = "X-Pantera-Cooldown";

    /**
     * Build a 403 Forbidden response for a blocked artifact.
     *
     * @param block Block details
     * @return HTTP response
     */
    Response forbidden(CooldownBlock block);

    /**
     * Repository type this factory handles.
     *
     * @return Repository type identifier (e.g. "npm", "maven")
     */
    String repoType();
}
