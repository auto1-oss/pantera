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
package com.auto1.pantera.docker.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.fake.FakeCatalogDocker;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.docker.proxy.ProxyDocker;
import com.auto1.pantera.http.ResponseBuilder;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Test;
import wtf.g4s8.hamcrest.json.JsonContains;
import wtf.g4s8.hamcrest.json.JsonHas;
import wtf.g4s8.hamcrest.json.JsonValueIs;
import wtf.g4s8.hamcrest.json.StringIsJson;

import java.util.Optional;

/**
 * Tests for {@link CacheDocker}.
 *
 * @since 0.3
 */
final class CacheDockerTest {

    @Test
    void createsCacheRepo() {
        final CacheDocker docker = new CacheDocker(
            new ProxyDocker("registry", (line, headers, body) -> ResponseBuilder.ok().completedFuture()),
            new AstoDocker("registry", new InMemoryStorage()),
            Optional.empty(),
            Optional.empty()
        );
        MatcherAssert.assertThat(
            docker.repo("test"),
            new IsInstanceOf(CacheRepo.class)
        );
    }

    @Test
    void loadsCatalogsFromOriginAndCache() {
        final int limit = 3;
        MatcherAssert.assertThat(
            new CacheDocker(
                fake("{\"repositories\":[\"one\",\"three\",\"four\"]}"),
                fake("{\"repositories\":[\"one\",\"two\"]}"),
                Optional.empty(),
                Optional.empty()
            ).catalog(Pagination.from("four", limit))
                .thenCompose(catalog -> catalog.json().asStringFuture()).join(),
            new StringIsJson.Object(
                new JsonHas(
                    "repositories",
                    new JsonContains(
                        new JsonValueIs("one"), new JsonValueIs("three"), new JsonValueIs("two")
                    )
                )
            )
        );
    }

    private static FakeCatalogDocker fake(final String catalog) {
        return new FakeCatalogDocker(() -> new Content.From(catalog.getBytes()));
    }
}
