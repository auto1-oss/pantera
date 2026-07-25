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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import java.nio.charset.StandardCharsets;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link MetaUpdate.ByJson}.
 * @since 0.9
 */
final class MetaUpdateByJsonTest {
    /**
     * Storage.
     */
    private Storage asto;

    @BeforeEach
    void setUp() {
        this.asto = new InMemoryStorage();
    }

    @Test
    void createsMetaFileWhenItNotExist() {
        final Key prefix = new Key.From("prefix");
        new MetaUpdate.ByJson(this.cliMeta())
            .update(new Key.From(prefix), this.asto)
            .join();
        // Generate meta.json from per-version files
        new PerVersionLayout(this.asto).generateMetaJson(prefix)
            .thenCompose(meta -> this.asto.save(
                new Key.From(prefix, "meta.json"),
                new com.auto1.pantera.asto.Content.From(meta.toString().getBytes(StandardCharsets.UTF_8))
            ))
            .toCompletableFuture()
            .join();
        MatcherAssert.assertThat(
            this.asto.exists(new Key.From(prefix, "meta.json")).join(),
            new IsEqual<>(true)
        );
    }

    @Test
    void updatesExistedMetaFile() {
        final Key prefix = new Key.From("prefix");
        new TestResource("json/simple-project-1.0.2.json")
            .saveTo(this.asto, new Key.From(prefix, "meta.json"));
        
        // Migrate existing meta.json to per-version layout
        this.migrateExistingMetaToPerVersion(prefix);
        
        new MetaUpdate.ByJson(this.cliMeta())
            .update(new Key.From(prefix), this.asto)
            .join();
        // Generate meta.json from per-version files
        new PerVersionLayout(this.asto).generateMetaJson(prefix)
            .thenCompose(meta -> this.asto.save(
                new Key.From(prefix, "meta.json"),
                new com.auto1.pantera.asto.Content.From(meta.toString().getBytes(StandardCharsets.UTF_8))
            ))
            .toCompletableFuture()
            .join();
        MatcherAssert.assertThat(
            new JsonFromMeta(this.asto, prefix).json()
                .getJsonObject("versions")
                .keySet(),
            Matchers.containsInAnyOrder("1.0.1", "1.0.2")
        );
    }

    /**
     * Split-brain regression guard (WS4-npm.3): a {@code npm publish --tag beta}
     * payload carries only {@code {"beta": "<version>"}} in its top-level
     * {@code dist-tags} — that must land in the durable sidecar, and
     * {@code latest} must be left alone (it was never requested).
     */
    @Test
    void persistsCustomDistTagFromPublishPayloadWithoutTouchingLatest() {
        final Key prefix = new Key.From("@hello/beta-project");
        final JsonObject uploaded = Json.createObjectBuilder()
            .add("name", "@hello/beta-project")
            .add(
                "dist-tags",
                Json.createObjectBuilder().add("beta", "1.0.0-beta.1")
            )
            .add(
                "versions",
                Json.createObjectBuilder().add(
                    "1.0.0-beta.1",
                    Json.createObjectBuilder()
                        .add("name", "@hello/beta-project")
                        .add("version", "1.0.0-beta.1")
                )
            )
            .build();
        new MetaUpdate.ByJson(uploaded).update(prefix, this.asto).join();
        final JsonObject tags = new PerVersionLayout(this.asto).readDistTags(prefix)
            .toCompletableFuture().join();
        MatcherAssert.assertThat(
            "beta tag persisted verbatim from the publish payload",
            tags.getString("beta"),
            new IsEqual<>("1.0.0-beta.1")
        );
        MatcherAssert.assertThat(
            "latest was never requested by this publish and stays absent",
            tags.containsKey("latest"),
            new IsEqual<>(false)
        );
    }

    /**
     * A publish payload with no {@code dist-tags} field at all (non-standard
     * client) must still leave the package installable — default to tagging
     * the published version as {@code latest}.
     */
    @Test
    void defaultsToLatestWhenPublishPayloadHasNoDistTags() {
        final Key prefix = new Key.From("@hello/no-tags-project");
        final JsonObject uploaded = Json.createObjectBuilder()
            .add("name", "@hello/no-tags-project")
            .add("version", "1.0.0")
            .build();
        new MetaUpdate.ByJson(uploaded).update(prefix, this.asto).join();
        MatcherAssert.assertThat(
            "latest defaults to the published version",
            new PerVersionLayout(this.asto).readDistTags(prefix)
                .toCompletableFuture().join().getString("latest"),
            new IsEqual<>("1.0.0")
        );
    }

    private JsonObject cliMeta() {
        return Json.createReader(
            new TestResource("json/cli_publish.json").asInputStream()
        ).readObject();
    }
    
    /**
     * Migrate existing meta.json versions to per-version layout.
     * This simulates the migration that would happen in production.
     * 
     * @param prefix Package prefix
     */
    private void migrateExistingMetaToPerVersion(final Key prefix) {
        final Key metaKey = new Key.From(prefix, "meta.json");
        if (!this.asto.exists(metaKey).join()) {
            return;
        }
        
        // Read existing meta.json
        final JsonObject meta = this.asto.value(metaKey)
            .thenCompose(com.auto1.pantera.asto.Content::asJsonObjectFuture)
            .toCompletableFuture()
            .join();
        
        // Extract all versions and write to per-version files
        if (meta.containsKey("versions")) {
            final JsonObject versions = meta.getJsonObject("versions");
            final PerVersionLayout layout = new PerVersionLayout(this.asto);
            
            for (String version : versions.keySet()) {
                final JsonObject versionData = versions.getJsonObject(version);
                layout.addVersion(prefix, version, versionData)
                    .toCompletableFuture()
                    .join();
            }
        }
    }
}
