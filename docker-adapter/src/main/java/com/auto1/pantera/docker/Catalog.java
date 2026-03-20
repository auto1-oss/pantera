/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker;

import com.auto1.pantera.asto.Content;

/**
 * Docker repositories catalog.
 */
public interface Catalog {

    /**
     * Read catalog in JSON format.
     *
     * @return Catalog in JSON format.
     */
    Content json();
}
