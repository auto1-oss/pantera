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

import com.auto1.pantera.docker.misc.OfficialImageName;

/**
 * The image name a docker cooldown row is keyed by ({@code artifact}).
 *
 * <p>The cooldown layer sits above {@code TrimmedDocker}, so request paths
 * still carry the Pantera repository name ({@code /v2/my-docker/nginx/…});
 * {@code library/} is only added further down, in {@code ProxyDocker}. Keying
 * on the raw path name made {@code my-docker/nginx} and
 * {@code my-docker/library/nginx} two unrelated artifacts, and neither
 * matched the name release dates are recorded under. The canonical key is
 * the image name as the upstream knows it: repository prefix removed
 * ({@code repository.name} already identifies the repo) and Docker Hub's
 * official-image rule applied — {@code library/nginx}.</p>
 *
 * @since 2.2.9
 */
public final class CooldownImageName {

    /**
     * Repository-name path prefix ({@code <repo>/}).
     */
    private final String prefix;

    /**
     * Official-image naming rule of the upstream.
     */
    private final OfficialImageName official;

    /**
     * Ctor.
     *
     * @param repoName Pantera repository name
     * @param official Official-image naming rule of the upstream
     */
    public CooldownImageName(final String repoName, final OfficialImageName official) {
        this.prefix = repoName + "/";
        this.official = official;
    }

    /**
     * Canonical cooldown artifact for an image name taken from a request path.
     *
     * @param requested Image name as it appears in the request path
     * @return Canonical artifact name
     */
    public String of(final String requested) {
        final String trimmed;
        if (requested.startsWith(this.prefix) && requested.length() > this.prefix.length()) {
            trimmed = requested.substring(this.prefix.length());
        } else {
            trimmed = requested;
        }
        return this.official.normalize(trimmed);
    }
}
