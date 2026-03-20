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

import com.auto1.pantera.nuget.metadata.PackageId;
import com.auto1.pantera.nuget.metadata.Version;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PackageIdentity}.
 *
 * @since 0.1
 */
public class PackageIdentityTest {

    /**
     * Example package identity.
     */
    private final PackageIdentity identity = new PackageIdentity(
        new PackageId("Newtonsoft.Json"),
        new Version("12.0.3")
    );

    @Test
    void shouldGenerateRootKey() {
        MatcherAssert.assertThat(
            this.identity.rootKey().string(),
            Matchers.is("newtonsoft.json/12.0.3")
        );
    }

    @Test
    void shouldGenerateNupkgKey() {
        MatcherAssert.assertThat(
            this.identity.nupkgKey().string(),
            Matchers.is("newtonsoft.json/12.0.3/newtonsoft.json.12.0.3.nupkg")
        );
    }

    @Test
    void shouldGenerateHashKey() {
        MatcherAssert.assertThat(
            this.identity.hashKey().string(),
            Matchers.is("newtonsoft.json/12.0.3/newtonsoft.json.12.0.3.nupkg.sha512")
        );
    }

    @Test
    void shouldGenerateNuspecKey() {
        MatcherAssert.assertThat(
            this.identity.nuspecKey().string(),
            Matchers.is("newtonsoft.json/12.0.3/newtonsoft.json.nuspec")
        );
    }
}
