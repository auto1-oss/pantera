/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.docker.proxy;

import com.artipie.docker.Layers;
import com.artipie.docker.Manifests;
import com.artipie.docker.Repo;
import com.artipie.docker.asto.Uploads;
import com.artipie.http.Slice;

/**
 * Proxy implementation of {@link Repo}.
 *
 * @since 0.3
 */
public final class ProxyRepo implements Repo {

    /**
     * Remote repository.
     */
    private final Slice remote;

    /**
     * Repository name (normalized for remote).
     */
    private final String name;
    
    /**
     * Original repository name (as requested by client).
     */
    private final String originalName;

    /**
     * @param remote Remote repository.
     * @param name Repository name (normalized).
     * @param originalName Original repository name.
     */
    public ProxyRepo(Slice remote, String name, String originalName) {
        this.remote = remote;
        this.name = name;
        this.originalName = originalName;
    }
    
    /**
     * @param remote Remote repository.
     * @param name Repository name.
     */
    public ProxyRepo(Slice remote, String name) {
        this(remote, name, name);
    }

    @Override
    public Layers layers() {
        return new ProxyLayers(this.remote, this.name);
    }

    @Override
    public Manifests manifests() {
        return new ProxyManifests(this.remote, this.name);
    }

    @Override
    public Uploads uploads() {
        throw new UnsupportedOperationException();
    }
}
