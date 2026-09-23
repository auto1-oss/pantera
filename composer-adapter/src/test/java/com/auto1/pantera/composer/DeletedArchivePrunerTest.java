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
package com.auto1.pantera.composer;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link DeletedArchivePruner}.
 * @since 2.2.9
 */
final class DeletedArchivePrunerTest {

    /**
     * Metadata key.
     */
    private static final Key P2 = new Key.From("p2", "qa", "helper.json");

    /**
     * Storage.
     */
    private InMemoryStorage storage;

    /**
     * Blocking view.
     */
    private BlockingStorage blocking;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.blocking = new BlockingStorage(this.storage);
        this.blocking.save(
            P2,
            Json.createObjectBuilder().add(
                "packages", Json.createObjectBuilder().add(
                    "qa/helper", Json.createObjectBuilder()
                        .add("1.0.0", DeletedArchivePrunerTest.version("1.0.0"))
                        .add("1.1.0", DeletedArchivePrunerTest.version("1.1.0"))
                )
            ).build().toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    @Test
    void removesTheVersionWhoseArchiveWasDeleted() {
        final int removed = new DeletedArchivePruner(this.storage)
            .afterDelete("artifacts/qa/helper/1.0.0/qa-helper-1.0.0.zip").join();
        MatcherAssert.assertThat(
            "one version entry is removed",
            removed, new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "only the other version stays in p2",
            this.versions(), new IsEqual<>(Set.of("1.1.0"))
        );
    }

    @Test
    void removesTheMetadataFileWhenThePackageFolderIsDeleted() {
        new DeletedArchivePruner(this.storage).afterDelete("artifacts/qa/helper").join();
        MatcherAssert.assertThat(this.blocking.exists(P2), new IsEqual<>(false));
    }

    @Test
    void ignoresPathsOutsideTheArchiveTree() {
        MatcherAssert.assertThat(
            new DeletedArchivePruner(this.storage).afterDelete("p2/qa/helper.json").join(),
            new IsEqual<>(0)
        );
    }

    private Set<String> versions() {
        final JsonObject json = Json.createReader(
            new StringReader(new String(this.blocking.value(P2), StandardCharsets.UTF_8))
        ).readObject();
        return json.getJsonObject("packages").getJsonObject("qa/helper").keySet();
    }

    private static JsonObject version(final String ver) {
        return Json.createObjectBuilder()
            .add("name", "qa/helper")
            .add("version", ver)
            .add(
                "dist", Json.createObjectBuilder()
                    .add("type", "zip")
                    .add(
                        "url",
                        String.format(
                            "http://localhost:8080/php/artifacts/qa/helper/%s/qa-helper-%s.zip",
                            ver, ver
                        )
                    )
            ).build();
    }
}
