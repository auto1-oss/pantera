/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */

package com.auto1.pantera.docker;

import com.auto1.pantera.docker.asto.Uploads;

/**
 * Docker repository files and metadata.
 * @since 0.1
 */
public interface Repo {

    /**
     * Repository layers.
     *
     * @return Layers.
     */
    Layers layers();

    /**
     * Repository manifests.
     *
     * @return Manifests.
     */
    Manifests manifests();

    /**
     * Repository uploads.
     *
     * @return Uploads.
     */
    Uploads uploads();
}
