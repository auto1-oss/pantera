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
package com.auto1.pantera.docker.asto;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.ManifestReference;

/**
 * Original storage layout that is compatible with reference Docker Registry implementation.
 */
public final class Layout {

    public static Key repositories() {
        return new Key.From("repositories");
    }

    public static Key blob(Digest digest) {
        return new Key.From(
            "blobs", digest.alg(), digest.hex().substring(0, 2), digest.hex(), "data"
        );
    }

    public static Key manifest(String repo, final ManifestReference ref) {
        return new Key.From(manifests(repo), ref.link().string());
    }

    public static Key tags(String repo) {
        return new Key.From(manifests(repo), "tags");
    }

    public static Key upload(String name, final String uuid) {
        return new Key.From(repositories(), name, "_uploads", uuid);
    }

    /**
     * Create manifests root key.
     *
     * @param repo Repository name.
     * @return Manifests key.
     */
    private static Key manifests(String repo) {
        return new Key.From(repositories(), repo, "_manifests");
    }
}
