/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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
