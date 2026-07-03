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
package com.auto1.pantera.npm.proxy.json;

import com.auto1.pantera.asto.test.TestResource;
import java.util.Set;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringStartsWith;
import org.junit.jupiter.api.Test;

/**
 * Client package content test.
 *
 * @since 0.1
 */
public class ClientContentTest {
    @Test
    public void getsValue() {
        final String url = "http://localhost";
        final String cached = new String(
            new TestResource("json/cached.json").asBytes()
        );
        final JsonObject json = new ClientContent(cached, url).value();
        final Set<String> vrsns = json.getJsonObject("versions").keySet();
        MatcherAssert.assertThat(
            "Could not find asset references",
            vrsns.isEmpty(),
            new IsEqual<>(false)
        );
        for (final String vers: vrsns) {
            MatcherAssert.assertThat(
                json.getJsonObject("versions").getJsonObject(vers)
                    .getJsonObject("dist").getString("tarball"),
                new StringStartsWith(url)
            );
        }
    }
}
