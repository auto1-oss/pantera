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
package com.auto1.pantera.api.v1.admin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Image name a docker reference stands for, as the cooldown tables and
 * negative cache store it.
 *
 * <p>Admins type what they typed at {@code docker pull}: {@code ubuntu},
 * {@code ubuntu:24.04}, {@code docker_group/ubuntu},
 * {@code localhost:8081/test_prefix/api/docker_group/ubuntu@sha256:...}.
 * Like the docker client, this drops the registry host, everything up to
 * and including the repository segment, and the tag or digest, and puts a
 * single-segment (official) name under {@code library/}.</p>
 *
 * @since 2.2.9
 */
final class DockerImageName {

    /**
     * URL scheme prefix.
     */
    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://");

    /**
     * Reference as typed.
     */
    private final String raw;

    /**
     * Names of the repositories the reference may route through.
     */
    private final Collection<String> repos;

    /**
     * Ctor.
     *
     * @param raw Reference as typed
     * @param repos Repository names in scope
     */
    DockerImageName(final String raw, final Collection<String> repos) {
        this.raw = raw == null ? "" : raw.trim();
        this.repos = repos;
    }

    /**
     * Stored image name.
     *
     * @return Name such as {@code library/ubuntu} or {@code myorg/app}
     */
    String name() {
        String ref = SCHEME.matcher(this.raw).replaceFirst("");
        final int digest = ref.indexOf('@');
        if (digest >= 0) {
            ref = ref.substring(0, digest);
        }
        List<String> parts = new ArrayList<>(
            Arrays.stream(ref.split("/")).filter(part -> !part.isEmpty()).toList()
        );
        if (parts.size() > 1 && DockerImageName.registryHost(parts.get(0))) {
            parts = parts.subList(1, parts.size());
        }
        for (int idx = 0; idx < parts.size() - 1; idx = idx + 1) {
            if (this.repos.contains(parts.get(idx))) {
                parts = parts.subList(idx + 1, parts.size());
                break;
            }
        }
        if (parts.isEmpty()) {
            return this.raw;
        }
        final List<String> name = new ArrayList<>(parts);
        final String last = name.get(name.size() - 1);
        final int tag = last.indexOf(':');
        if (tag > 0) {
            name.set(name.size() - 1, last.substring(0, tag));
        }
        if (name.size() == 1) {
            name.add(0, "library");
        }
        return String.join("/", name).toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a leading segment is a registry host, as the docker client
     * decides it: it has a dot or a port, or it is {@code localhost}.
     *
     * @param segment First segment
     * @return True for a host
     */
    private static boolean registryHost(final String segment) {
        return segment.indexOf('.') >= 0 || segment.indexOf(':') >= 0
            || "localhost".equals(segment);
    }
}
