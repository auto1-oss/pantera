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

import java.util.Locale;

/**
 * The artifact name an unblock request addresses, in the form the cooldown
 * block is stored under.
 *
 * <p>Maven and Gradle blocks are keyed by the dotted
 * {@code groupId.artifactId} (e.g. {@code software.amazon.awssdk.annotations}).
 * The documented {@code groupId:artifactId} form -- and the path form
 * {@code groupId-path/artifactId} -- matched no block, so the unblock
 * answered 204 and changed nothing. Both are normalised to the dotted key;
 * every other format is passed through unchanged.</p>
 *
 * @since 2.2.9
 */
final class UnblockArtifactName {

    /**
     * Repository type.
     */
    private final String repoType;

    /**
     * Requested artifact name.
     */
    private final String artifact;

    /**
     * Ctor.
     * @param repoType Repository type
     * @param artifact Requested artifact name
     */
    UnblockArtifactName(final String repoType, final String artifact) {
        this.repoType = repoType;
        this.artifact = artifact;
    }

    /**
     * The block key.
     * @return Normalised artifact name
     */
    String value() {
        final String type = this.repoType == null ? "" : this.repoType.toLowerCase(Locale.ROOT);
        final String result;
        if (type.startsWith("maven") || type.startsWith("gradle")) {
            String name = this.artifact;
            while (name.startsWith("/")) {
                name = name.substring(1);
            }
            result = name.replace(':', '.').replace('/', '.');
        } else {
            result = this.artifact;
        }
        return result;
    }
}
