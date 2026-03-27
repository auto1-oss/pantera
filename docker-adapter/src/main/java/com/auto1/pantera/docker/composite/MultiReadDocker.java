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
package com.auto1.pantera.docker.composite;

import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.misc.JoinedCatalogSource;
import com.auto1.pantera.docker.misc.Pagination;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Multi-read {@link Docker} implementation.
 * It delegates all read operations to multiple other {@link Docker} instances.
 * List of Docker instances is prioritized.
 * It means that if more then one of repositories contains an image for given name
 * then image from repository coming first is returned.
 * Write operations are not supported.
 * Might be used to join multiple proxy Dockers into single repository.
 */
public final class MultiReadDocker implements Docker {


    /**
     * Dockers for reading.
     */
    private final List<Docker> dockers;

    /**
     * @param dockers Dockers for reading.
     */
    public MultiReadDocker(Docker... dockers) {
        this(Arrays.asList(dockers));
    }

    /**
     * Ctor.
     *
     * @param dockers Dockers for reading.
     */
    public MultiReadDocker(List<Docker> dockers) {
        this.dockers = dockers;
    }

    @Override
    public String registryName() {
        return dockers.getFirst().registryName();
    }

    @Override
    public Repo repo(String name) {
        return new MultiReadRepo(
            name,
            this.dockers.stream().map(docker -> docker.repo(name)).collect(Collectors.toList())
        );
    }

    @Override
    public CompletableFuture<Catalog> catalog(Pagination pagination) {
        return new JoinedCatalogSource(this.dockers, pagination).catalog();
    }
}
