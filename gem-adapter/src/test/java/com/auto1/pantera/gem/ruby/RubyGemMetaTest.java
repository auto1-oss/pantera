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
package com.auto1.pantera.gem.ruby;

import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.gem.JsonMetaFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.json.Json;
import javax.json.JsonObjectBuilder;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wtf.g4s8.hamcrest.json.JsonContains;
import wtf.g4s8.hamcrest.json.JsonHas;
import wtf.g4s8.hamcrest.json.JsonValueIs;

/**
 * Test case for {@link RubyGemMeta}.
 * @since 1.3
 */
final class RubyGemMetaTest {
    @Test
    void generateValidMeta(final @TempDir Path tmp) throws Exception {
        final Path gem = tmp.resolve("target.gem");
        Files.write(gem, new TestResource("builder-3.2.4.gem").asBytes());
        final RubyGemMeta meta = new SharedRuntime().apply(RubyGemMeta::new)
            .toCompletableFuture().join();
        final JsonObjectBuilder json = Json.createObjectBuilder();
        meta.info(gem).print(new JsonMetaFormat(json));
        MatcherAssert.assertThat(
            json.build(),
            Matchers.allOf(
                new JsonHas("name", "builder"),
                new JsonHas("version", "3.2.4"),
                new JsonHas("platform", "ruby"),
                new JsonHas("authors", new JsonContains(new JsonValueIs("Jim Weirich"))),
                new JsonHas(
                    "info",
                    new JsonValueIs(
                        Matchers.startsWith("Builder provides a number of builder objects")
                    )
                ),
                new JsonHas("licenses", new JsonContains(new JsonValueIs("MIT"))),
                new JsonHas("homepage_uri", "http://onestepback.org")
            )
        );
    }
}
