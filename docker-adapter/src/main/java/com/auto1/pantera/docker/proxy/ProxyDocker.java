/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.docker.proxy;

import com.artipie.asto.Content;
import com.artipie.docker.Catalog;
import com.artipie.docker.Docker;
import com.artipie.docker.Repo;
import com.artipie.docker.misc.Pagination;
import com.artipie.http.Headers;
import com.artipie.http.RsStatus;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;

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
     * @param registryName Name of the Artipie registry
     * @param remote Remote repository slice
     * @param remoteUri Remote registry URI
     */
    public ProxyDocker(String registryName, Slice remote, URI remoteUri) {
        this.registryName = registryName;
        this.remote = remote;
        this.remoteUri = remoteUri;
    }
    
    /**
     * @param registryName Name of the Artipie registry
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
            host.equals("registry-1.docker.io") ||
            host.equals("docker.io") ||
            host.equals("hub.docker.com")
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
