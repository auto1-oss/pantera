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
import com.artipie.http.client.ClientSlices;

/**
 * Proxy implementation of {@link Repo}.
 * <p>
 * Provides access to Docker repository data through a remote registry,
 * with support for following HTTP redirects to CDN URLs.
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
     * Client slices for following redirects to CDN URLs.
     * May be null if redirect following is not supported.
     */
    private final ClientSlices clients;

    /**
     * @param remote Remote repository.
     * @param name Repository name (normalized).
     * @param originalName Original repository name.
     */
    public ProxyRepo(Slice remote, String name, String originalName) {
        this(remote, name, originalName, null);
    }

    /**
     * @param remote Remote repository.
     * @param name Repository name (normalized).
     * @param originalName Original repository name.
     * @param clients Client slices for following redirects.
     */
    public ProxyRepo(Slice remote, String name, String originalName, ClientSlices clients) {
        this.remote = remote;
        this.name = name;
        this.originalName = originalName;
        this.clients = clients;
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
        return new ProxyLayers(this.remote, this.name, this.clients);
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
