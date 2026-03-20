/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */

package com.auto1.pantera.docker.asto;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.misc.Pagination;

import java.util.concurrent.CompletableFuture;

/**
 * Asto {@link Docker} implementation.
 */
public final class AstoDocker implements Docker {

    private final String registryName;

    private final Storage storage;

    public AstoDocker(String registryName, Storage storage) {
        this.registryName = registryName;
        this.storage = storage;
    }

    @Override
    public String registryName() {
        return registryName;
    }

    @Override
    public Repo repo(String name) {
        return new AstoRepo(this.storage, name);
    }

    @Override
    public CompletableFuture<Catalog> catalog(Pagination pagination) {
        final Key root = Layout.repositories();
        return this.storage.list(root).thenApply(keys -> new AstoCatalog(root, keys, pagination));
    }
}
