/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
