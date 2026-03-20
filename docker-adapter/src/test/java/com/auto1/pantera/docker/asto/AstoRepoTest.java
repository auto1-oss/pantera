/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.asto;

import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.Repo;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AstoRepo}.
 */
final class AstoRepoTest {

    /**
     * Layers tested.
     */
    private Repo repo;

    @BeforeEach
    void setUp() {
        this.repo = new AstoRepo(new InMemoryStorage(), "test");
    }

    @Test
    void shouldCreateAstoLayers() {
        MatcherAssert.assertThat(
            this.repo.layers(),
            Matchers.instanceOf(AstoLayers.class)
        );
    }

    @Test
    void shouldCreateAstoManifests() {
        MatcherAssert.assertThat(
            this.repo.manifests(),
            Matchers.instanceOf(AstoManifests.class)
        );
    }
}
