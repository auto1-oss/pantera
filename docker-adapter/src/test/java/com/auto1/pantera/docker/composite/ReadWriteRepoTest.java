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
package com.auto1.pantera.docker.composite;

import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.asto.AstoRepo;
import com.auto1.pantera.docker.asto.Uploads;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ReadWriteRepo}.
 *
 * @since 0.3
 */
final class ReadWriteRepoTest {

    @Test
    void createsReadWriteLayers() {
        MatcherAssert.assertThat(
            new ReadWriteRepo(repo(), repo()).layers(),
            new IsInstanceOf(ReadWriteLayers.class)
        );
    }

    @Test
    void createsReadWriteManifests() {
        MatcherAssert.assertThat(
            new ReadWriteRepo(repo(), repo()).manifests(),
            new IsInstanceOf(ReadWriteManifests.class)
        );
    }

    @Test
    void createsWriteUploads() {
        final Uploads uploads = new Uploads(new InMemoryStorage(), "test");
        MatcherAssert.assertThat(
            new ReadWriteRepo(
                repo(),
                new Repo() {
                    @Override
                    public Layers layers() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Manifests manifests() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Uploads uploads() {
                        return uploads;
                    }
                }
            ).uploads(),
            new IsEqual<>(uploads)
        );
    }

    private static Repo repo() {
        return new AstoRepo(new InMemoryStorage(), "test-repo");
    }
}
