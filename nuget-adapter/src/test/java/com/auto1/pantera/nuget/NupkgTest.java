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
package com.auto1.pantera.nuget;

import com.auto1.pantera.nuget.metadata.Nuspec;
import java.io.ByteArrayInputStream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Nupkg}.
 *
 * @since 0.1
 */
class NupkgTest {

    /**
     * Resource `newtonsoft.json.12.0.3.nupkg` name.
     */
    private String name;

    @BeforeEach
    void init() {
        this.name = "newtonsoft.json.12.0.3.nupkg";
    }

    @Test
    void shouldExtractNuspec() {
        final Nuspec nuspec = new Nupkg(
            new ByteArrayInputStream(new NewtonJsonResource(this.name).bytes())
        ).nuspec();
        MatcherAssert.assertThat(
            nuspec.id().normalized(),
            Matchers.is("newtonsoft.json")
        );
    }
}
