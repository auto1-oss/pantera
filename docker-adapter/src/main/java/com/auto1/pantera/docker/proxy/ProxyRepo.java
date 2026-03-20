/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.proxy;

import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.asto.Uploads;
import com.auto1.pantera.http.Slice;

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
