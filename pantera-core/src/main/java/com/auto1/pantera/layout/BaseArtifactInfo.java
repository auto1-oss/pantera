/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.layout;

import java.util.HashMap;
import java.util.Map;

/**
 * Base implementation of ArtifactInfo.
 *
 * @since 1.0
 */
public final class BaseArtifactInfo implements StorageLayout.ArtifactInfo {

    /**
     * Repository name.
     */
    private final String repo;

    /**
     * Artifact name.
     */
    private final String artifactName;

    /**
     * Artifact version.
     */
    private final String ver;

    /**
     * Additional metadata.
     */
    private final Map<String, String> meta;

    /**
     * Constructor.
     *
     * @param repository Repository name
     * @param name Artifact name
     * @param version Artifact version
     */
    public BaseArtifactInfo(final String repository, final String name, final String version) {
        this(repository, name, version, new HashMap<>());
    }

    /**
     * Constructor with metadata.
     *
     * @param repository Repository name
     * @param name Artifact name
     * @param version Artifact version
     * @param metadata Additional metadata
     */
    public BaseArtifactInfo(
        final String repository,
        final String name,
        final String version,
        final Map<String, String> metadata
    ) {
        this.repo = repository;
        this.artifactName = name;
        this.ver = version;
        this.meta = new HashMap<>(metadata);
    }

    @Override
    public String repository() {
        return this.repo;
    }

    @Override
    public String name() {
        return this.artifactName;
    }

    @Override
    public String version() {
        return this.ver;
    }

    @Override
    public String metadata(final String key) {
        return this.meta.get(key);
    }

    /**
     * Add metadata.
     *
     * @param key Metadata key
     * @param value Metadata value
     * @return This instance
     */
    public BaseArtifactInfo withMetadata(final String key, final String value) {
        this.meta.put(key, value);
        return this;
    }
}
