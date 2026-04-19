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
