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
package com.auto1.pantera.nuget.metadata;

import com.auto1.pantera.asto.test.TestResource;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;

/**
 * Test for {@link CatalogEntry.FromNuspec}.
 * @since 1.5
 */
class CatalogEntryFromNuspecTest {

    @Test
    void createsCatalogEntryForNewtonsoftJson() throws JSONException {
        JSONAssert.assertEquals(
            new CatalogEntry.FromNuspec(
                new Nuspec.Xml(
                    new TestResource("newtonsoft.json/12.0.3/newtonsoft.json.nuspec")
                        .asInputStream()
                )
            ).asJson().toString(),
            new String(
                new TestResource("CatalogEntryFromNuspecTest/newtonsoftjson.json").asBytes(),
                StandardCharsets.UTF_8
            ),
            true
        );
    }

    @Test
    void createsCatalogEntryForSomePackage() throws JSONException {
        JSONAssert.assertEquals(
            new CatalogEntry.FromNuspec(
                new Nuspec.Xml(
                    new TestResource("CatalogEntryFromNuspecTest/some_package.nuspec")
                        .asInputStream()
                )
            ).asJson().toString(),
            new String(
                new TestResource("CatalogEntryFromNuspecTest/some_package.json").asBytes(),
                StandardCharsets.UTF_8
            ),
            true
        );
    }
}
