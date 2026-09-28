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
package com.auto1.pantera.docker.misc;

import java.net.URI;
import java.util.Collection;
import java.util.Set;

/**
 * Docker Hub official-image naming rule: on Docker Hub a single-segment
 * image name ({@code nginx}) is an alias of {@code library/nginx}.
 *
 * <p>The one place this rule lives. {@code ProxyDocker} applies it to the
 * upstream path; the cooldown layer applies it to its block keys so both
 * spellings of one image share one cooldown row.</p>
 *
 * @since 2.2.9
 */
public final class OfficialImageName {

    /**
     * Docker Hub registry hosts.
     */
    private static final Set<String> HUB_HOSTS = Set.of(
        "registry-1.docker.io", "docker.io", "hub.docker.com"
    );

    /**
     * Official images namespace.
     */
    private static final String LIBRARY = "library/";

    /**
     * Whether the upstream is Docker Hub.
     */
    private final boolean hub;

    /**
     * Rule for images proxied from any of the given remotes: applies when at
     * least one of them is Docker Hub.
     *
     * @param remotes Remote registry URIs (nullable entries tolerated)
     */
    public OfficialImageName(final Collection<URI> remotes) {
        this(remotes.stream().anyMatch(OfficialImageName::isHub));
    }

    /**
     * Rule for images proxied from one remote.
     *
     * @param remote Remote registry URI, or {@code null} when unknown
     */
    public OfficialImageName(final URI remote) {
        this(isHub(remote));
    }

    /**
     * Ctor.
     *
     * @param hub Whether the upstream is Docker Hub
     */
    public OfficialImageName(final boolean hub) {
        this.hub = hub;
    }

    /**
     * Canonical image name: {@code library/<name>} for a single-segment
     * name on Docker Hub, the name unchanged otherwise.
     *
     * @param name Image name without the Pantera repository prefix
     * @return Canonical name
     */
    public String normalize(final String name) {
        if (this.hub && name.indexOf('/') < 0) {
            return LIBRARY + name;
        }
        return name;
    }

    /**
     * The other spelling of {@code name} under this rule, if it has one:
     * {@code library/nginx} and {@code nginx} are aliases on Docker Hub.
     *
     * @param name Image name without the Pantera repository prefix
     * @return Alias spelling, or {@code name} itself when there is none
     */
    public String alias(final String name) {
        if (this.hub && name.startsWith(LIBRARY)
            && name.indexOf('/', LIBRARY.length()) < 0) {
            return name.substring(LIBRARY.length());
        }
        return this.normalize(name);
    }

    /**
     * Whether a remote URI points at Docker Hub.
     *
     * @param remote Remote URI (nullable)
     * @return True for Docker Hub hosts
     */
    private static boolean isHub(final URI remote) {
        return remote != null && remote.getHost() != null
            && HUB_HOSTS.contains(remote.getHost());
    }
}
