/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.fake;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Tags;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.misc.Pagination;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Auxiliary class for tests for {@link com.auto1.pantera.docker.cache.CacheManifests}.
 */
public final class FakeManifests implements Manifests {

    private final Manifests mnfs;

    /**
     * @param type Type of manifests.
     * @param code Code of manifests.
     */
    public FakeManifests(final String type, final String code) {
        this.mnfs = manifests(type, code);
    }

    @Override
    public CompletableFuture<Manifest> put(final ManifestReference ref, final Content content) {
        return this.mnfs.put(ref, content);
    }

    @Override
    public CompletableFuture<Optional<Manifest>> get(final ManifestReference ref) {
        return this.mnfs.get(ref);
    }

    @Override
    public CompletableFuture<Tags> tags(Pagination pagination) {
        return this.mnfs.tags(pagination);
    }

    /**
     * Creates manifests.
     *
     * @param type Type of manifests.
     * @param code Code of manifests.
     * @return Manifests.
     */
    private static Manifests manifests(final String type, final String code) {
        return switch (type) {
            case "empty" -> new EmptyGetManifests();
            case "full" -> new FullGetManifests(code);
            case "faulty" -> new FaultyGetManifests();
            default -> throw new IllegalArgumentException("Unsupported type:" + type);
        };
    }
}
