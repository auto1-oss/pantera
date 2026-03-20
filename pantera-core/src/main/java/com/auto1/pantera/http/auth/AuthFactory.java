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
package com.auto1.pantera.http.auth;

import com.amihaiemil.eoyaml.YamlMapping;

/**
 * Authentication factory creates auth instance from yaml settings.
 * Yaml settings is
 * <a href="https://github.com/pantera/pantera/wiki/Configuration">pantera main config</a>.
 * @since 1.3
 */
public interface AuthFactory {

    /**
     * Construct auth instance.
     * @param conf Yaml configuration
     * @return Instance of {@link Authentication}
     */
    Authentication getAuthentication(YamlMapping conf);

}
