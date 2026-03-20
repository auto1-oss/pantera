/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.gem;

import java.nio.file.Path;

/**
 * Gem repository index.
 *
 * @since 1.0
 */
public interface GemIndex {

    /**
     * Update index.
     * @param path Repository index path
     */
    void update(Path path);
}
