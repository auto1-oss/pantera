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
package com.auto1.pantera.api.v1;

/**
 * The {@code docker pull} reference for a path in a docker repository's
 * storage: {@code <repo>/<image>:<tag>}, or {@code <repo>/<image>@<digest>}
 * for a manifest revision. Images are served under the repository name
 * ({@code <host>/<repo>/<image>}), so a reference without it -- or without
 * the tag the path names -- pulled the wrong image or nothing.
 *
 * @since 2.2.9
 */
final class DockerPullReference {

    /**
     * Repository name.
     */
    private final String repo;

    /**
     * Storage path, e.g.
     * {@code docker/registry/v2/repositories/app/_manifests/tags/1.0/current/link}.
     */
    private final String path;

    /**
     * Ctor.
     * @param repo Repository name
     * @param path Storage path
     */
    DockerPullReference(final String repo, final String path) {
        this.repo = repo;
        this.path = path;
    }

    /**
     * The reference, with {@code <image>} / {@code <tag>} placeholders for
     * the parts the path does not name.
     * @return Reference without the host
     */
    String value() {
        final String[] parts = this.path.split("/");
        int start = -1;
        for (int idx = 0; idx < parts.length; idx += 1) {
            if ("repositories".equals(parts[idx])) {
                start = idx + 1;
                break;
            }
        }
        final StringBuilder image = new StringBuilder();
        String suffix = ":<tag>";
        if (start > 0) {
            for (int idx = start; idx < parts.length; idx += 1) {
                final String part = parts[idx];
                if (part.startsWith("_")) {
                    suffix = DockerPullReference.suffix(parts, idx);
                    break;
                }
                if (image.length() > 0) {
                    image.append('/');
                }
                image.append(part);
            }
        }
        final String name = image.length() == 0 ? "<image>" : image.toString();
        return String.format("%s/%s%s", this.repo, name, suffix);
    }

    /**
     * Tag or digest suffix named after the {@code _manifests} segment.
     * @param parts Path segments
     * @param idx Index of the first {@code _...} segment
     * @return {@code :tag}, {@code @alg:hex}, or {@code :<tag>}
     */
    private static String suffix(final String[] parts, final int idx) {
        String suffix = ":<tag>";
        if ("_manifests".equals(parts[idx]) && idx + 2 < parts.length) {
            if ("tags".equals(parts[idx + 1])) {
                suffix = ":" + parts[idx + 2];
            } else if ("revisions".equals(parts[idx + 1]) && idx + 3 < parts.length) {
                suffix = "@" + parts[idx + 2] + ":" + parts[idx + 3];
            }
        }
        return suffix;
    }
}
