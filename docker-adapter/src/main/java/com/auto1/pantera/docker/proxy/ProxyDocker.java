/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.docker.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

/**
 * Proxy {@link Docker} implementation.
 */
public final class ProxyDocker implements Docker {

    private final String registryName;
    /**
     * Remote repository.
     */
    private final Slice remote;
    
    /**
     * Remote registry URI for Docker Hub detection.
     */
    private final URI remoteUri;

    /**
     * @param registryName Name of the Pantera registry
     * @param remote Remote repository slice
     * @param remoteUri Remote registry URI
     */
    public ProxyDocker(String registryName, Slice remote, URI remoteUri) {
        this.registryName = registryName;
        this.remote = remote;
        this.remoteUri = remoteUri;
    }
    
    /**
     * @param registryName Name of the Pantera registry
     * @param remote Remote repository slice
     */
    public ProxyDocker(String registryName, Slice remote) {
        this(registryName, remote, null);
    }

    @Override
    public String registryName() {
        return registryName;
    }

    /**
     * Get upstream URL.
     * @return Upstream URL or "unknown" if not set
     */
    public String upstreamUrl() {
        return this.remoteUri != null ? this.remoteUri.toString() : "unknown";
    }

    @Override
    public Repo repo(String name) {
        // Normalize name for Docker Hub
        String normalizedName = this.normalizeRepoName(name);
        return new ProxyRepo(this.remote, normalizedName, name);
    }
    
    /**
     * Normalize repository name for Docker Hub.
     * Docker Hub uses 'library/' prefix for official images.
     * @param name Original repository name
     * @return Normalized repository name
     */
    private String normalizeRepoName(String name) {
        if (this.isDockerHub() && !name.contains("/")) {
            // For Docker Hub, official images need 'library/' prefix
            return "library/" + name;
        }
        return name;
    }
    
    /**
     * Check if remote registry is Docker Hub.
     * @return true if Docker Hub, false otherwise
     */
    private boolean isDockerHub() {
        if (this.remoteUri == null) {
            return false;
        }
        String host = this.remoteUri.getHost();
        return host != null && (
            "registry-1.docker.io".equals(host) ||
            "docker.io".equals(host) ||
            "hub.docker.com".equals(host)
        );
    }

    @Override
    public CompletableFuture<Catalog> catalog(Pagination pagination) {
        return new ResponseSink<>(
            this.remote.response(
                new RequestLine(RqMethod.GET, pagination.uriWithPagination("/v2/_catalog")),
                Headers.EMPTY,
                Content.EMPTY
            ),
            response -> {
                if (response.status() == RsStatus.OK) {
                    Catalog res = response::body;
                    return CompletableFuture.completedFuture(res);
                }
                // CRITICAL: Consume body to prevent Vert.x request leak
                return response.body().asBytesFuture().thenCompose(
                    ignored -> CompletableFuture.failedFuture(
                        new IllegalArgumentException("Unexpected status: " + response.status())
                    )
                );
            }
        ).result();
    }
}
