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
package com.auto1.pantera.security.policy;

import com.auto1.pantera.asto.factory.Config;

/**
 * Factory to create {@link Policy} instance.
 * @since 1.2
 */
public interface PolicyFactory {

    /**
     * Create {@link Policy} from provided {@link YamlPolicyConfig}.
     * @param config Configuration
     * @return Instance of {@link Policy}
     */
    Policy<?> getPolicy(Config config);

}
