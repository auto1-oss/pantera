/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */

package com.auto1.pantera.docker;

import com.auto1.pantera.docker.misc.Pagination;

import java.util.concurrent.CompletableFuture;

/**
 * Docker registry storage main object.
 * @see com.auto1.pantera.docker.asto.AstoDocker
 */
public interface Docker {

    /**
     * Gets registry name.
     *
     * @return Registry name.
     */
    String registryName();

    /**
     * Docker repo by name.
     *
     * @param name Repository name
     * @return Repository object
     */
    Repo repo(String name);

    /**
     * Docker repositories catalog.
     *
     * @param pagination  Pagination parameters.
     * @return Catalog.
     */
    CompletableFuture<Catalog> catalog(Pagination pagination);

    /**
     * The image name this instance will actually address storage with.
     *
     * <p>Identity for a plain registry. A path-hosted repository is wrapped in
     * {@code TrimmedDocker}, which strips the repository-name prefix before
     * touching storage — so the name a client sent and the name the artifact
     * is stored under differ. Anything that needs the artifact's real storage
     * key (the index's {@code path_prefix}, say) has to resolve the name
     * through here rather than reuse the request's, or it records a key that
     * points nowhere.</p>
     *
     * @param name Image name as the client addressed it
     * @return Image name as storage sees it
     */
    default String resolveName(String name) {
        return name;
    }
}
