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
package com.auto1.pantera.npm;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.util.Set;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Test for {@link TarballDeletePruner}.
 *
 * @since 2.2.9
 */
final class TarballDeletePrunerTest {

    @ParameterizedTest
    @ValueSource(strings = {"qa-pkg", "@qa/pkg"})
    void deletingATarballUnpublishesItsVersion(final String name) {
        final Storage asto = TarballDeletePrunerTest.published(name);
        asto.delete(new Key.From(name, "-", name + "-2.0.0.tgz")).join();
        new TarballDeletePruner(asto).afterDelete(name + "/-/" + name + "-2.0.0.tgz").join();
        final JsonObject meta = new PerVersionLayout(asto)
            .generateMetaJson(new Key.From(name)).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "the deleted version is no longer listed",
            meta.getJsonObject("versions").keySet(), new IsEqual<>(Set.of("1.0.0"))
        );
        MatcherAssert.assertThat(
            "latest falls back to the remaining version",
            meta.getJsonObject("dist-tags").getString("latest"), new IsEqual<>("1.0.0")
        );
        MatcherAssert.assertThat(
            "a tag pointing at the deleted version is dropped",
            meta.getJsonObject("dist-tags").containsKey("next"), new IsEqual<>(false)
        );
    }

    @Test
    void deletingTheTarballFolderUnpublishesEveryVersion() {
        final Storage asto = TarballDeletePrunerTest.published("qa-pkg");
        asto.delete(new Key.From("qa-pkg", "-", "qa-pkg-1.0.0.tgz")).join();
        asto.delete(new Key.From("qa-pkg", "-", "qa-pkg-2.0.0.tgz")).join();
        new TarballDeletePruner(asto).afterDelete("qa-pkg/-").join();
        MatcherAssert.assertThat(
            new PerVersionLayout(asto).hasVersions(new Key.From("qa-pkg"))
                .toCompletableFuture().join(),
            new IsEqual<>(false)
        );
    }

    @Test
    void keepsAVersionWhoseTarballIsStillStored() {
        final Storage asto = TarballDeletePrunerTest.published("qa-pkg");
        new TarballDeletePruner(asto).afterDelete("qa-pkg/-/qa-pkg-2.0.0.tgz").join();
        MatcherAssert.assertThat(
            new PerVersionLayout(asto).listVersions(new Key.From("qa-pkg"))
                .toCompletableFuture().join(),
            new IsEqual<>(Set.of("1.0.0", "2.0.0"))
        );
    }

    /**
     * A package with versions 1.0.0 and 2.0.0 (latest and next on 2.0.0).
     * @param name Package name
     * @return Storage
     */
    private static Storage published(final String name) {
        final Storage asto = new InMemoryStorage();
        final PerVersionLayout layout = new PerVersionLayout(asto);
        final Key pkg = new Key.From(name);
        for (final String ver : new String[] {"1.0.0", "2.0.0"}) {
            layout.addVersion(
                pkg, ver,
                Json.createObjectBuilder()
                    .add("name", name).add("version", ver)
                    .add(
                        "dist",
                        Json.createObjectBuilder().add(
                            "tarball",
                            String.format("http://localhost/npm/%s/-/%s-%s.tgz", name, name, ver)
                        )
                    ).build()
            ).toCompletableFuture().join();
            asto.save(new Key.From(name, "-", name + "-" + ver + ".tgz"), Content.EMPTY).join();
        }
        layout.mergeDistTags(
            pkg, Json.createObjectBuilder().add("latest", "2.0.0").add("next", "2.0.0").build()
        ).toCompletableFuture().join();
        return asto;
    }
}
