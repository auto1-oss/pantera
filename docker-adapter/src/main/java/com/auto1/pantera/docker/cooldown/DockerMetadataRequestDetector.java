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
package com.auto1.pantera.docker.cooldown;

import com.auto1.pantera.cooldown.metadata.MetadataRequestDetector;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Docker metadata request detector implementing cooldown SPI.
 * Identifies Docker tags/list requests by matching {@code /v2/{name}/tags/list}.
 *
 * @since 2.2.0
 */
public final class DockerMetadataRequestDetector implements MetadataRequestDetector {

    /**
     * Pattern matching Docker tags/list endpoint: {@code /v2/{name}/tags/list}.
     * The name group captures the full image repository name (e.g. "library/nginx").
     */
    private static final Pattern TAGS_LIST = Pattern.compile(
        "^/v2/(?<name>.+)/tags/list$"
    );

    /**
     * Repository type identifier.
     */
    private static final String REPO_TYPE = "docker";

    @Override
    public boolean isMetadataRequest(final String path) {
        return TAGS_LIST.matcher(path).matches();
    }

    @Override
    public Optional<String> extractPackageName(final String path) {
        final Matcher matcher = TAGS_LIST.matcher(path);
        if (matcher.matches()) {
            return Optional.of(matcher.group("name"));
        }
        return Optional.empty();
    }

    @Override
    public String repoType() {
        return REPO_TYPE;
    }
}
