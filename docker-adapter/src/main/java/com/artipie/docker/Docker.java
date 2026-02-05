/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */

package com.artipie.docker;

import com.artipie.asto.Storage;
import com.artipie.docker.misc.Pagination;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Docker registry storage main object.
 * @see com.artipie.docker.asto.AstoDocker
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
     * Get underlying storage if available.
     * Used for stream-through caching optimization.
     *
     * @return Optional storage
     */
    default Optional<Storage> storage() {
        return Optional.empty();
    }
}
