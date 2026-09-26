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
package com.auto1.pantera.adapters.docker;

import com.auto1.pantera.docker.cache.DockerProxyCooldownInspector;
import com.auto1.pantera.publishdate.DbPublishDateRegistry;
import java.time.Instant;

/**
 * Persists docker release dates where the cooldown lookup reads them.
 *
 * <p>{@code JdbcCooldownService} falls back to the publish-date registry
 * (after an inspector miss — restart, another instance, cache expiry) with
 * exactly {@code (request.repoType(), request.artifact(), request.version())}.
 * Dates used to be persisted under repo type {@code docker} while docker
 * cooldown requests carry the repository's own type ({@code docker-proxy}),
 * so the fallback never matched and fresh tags were allowed. Rows are keyed
 * by the repository type here; the artifact arrives already canonical from
 * {@link DockerProxyCooldownInspector}.</p>
 *
 * @since 2.2.9
 */
public final class DockerReleaseDates implements DockerProxyCooldownInspector.ReleaseDateCallback {

    /**
     * Registry source id for dates read from the image config.
     */
    private static final String SOURCE = "manifest-config";

    /**
     * Publish-date registry.
     */
    private final DbPublishDateRegistry registry;

    /**
     * Repository type the cooldown requests of this repository carry.
     */
    private final String repoType;

    /**
     * Ctor.
     *
     * @param registry Publish-date registry
     * @param repoType Repository type (e.g. {@code docker-proxy})
     */
    public DockerReleaseDates(final DbPublishDateRegistry registry, final String repoType) {
        this.registry = registry;
        this.repoType = repoType;
    }

    @Override
    public void onRelease(final String artifact, final String version, final Instant release) {
        this.registry.persist(this.repoType, artifact, version, release, SOURCE);
    }
}
