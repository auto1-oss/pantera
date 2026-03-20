/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import java.nio.charset.StandardCharsets;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MetaUpdate.ByTgz}.
 * @since 0.9
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
final class MetaUpdateByTgzTest {
    /**
     * Storage.
     */
    private Storage asto;

    @BeforeEach
    void setUp() {
        this.asto = new InMemoryStorage();
    }

    @Test
    void createsMetaFileWhenItNotExist() throws InterruptedException {
        final Key prefix = new Key.From("@hello/simple-npm-project");
        this.updateByTgz(prefix);
        // After update, generate meta.json from per-version files
        new PerVersionLayout(this.asto).generateMetaJson(prefix)
            .thenCompose(meta -> this.asto.save(
                new Key.From(prefix, "meta.json"),
                new com.auto1.pantera.asto.Content.From(meta.toString().getBytes(StandardCharsets.UTF_8))
            ))
            .toCompletableFuture()
            .join();
        MatcherAssert.assertThat(
            new BlockingStorage(this.asto).exists(new Key.From(prefix, "meta.json")),
            new IsEqual<>(true)
        );
    }

    @Test
    void updatesExistedMetaFile() {
        final Key prefix = new Key.From("@hello/simple-npm-project");
        new TestResource("storage/@hello/simple-npm-project/meta.json")
            .saveTo(this.asto, new Key.From(prefix, "meta.json"));
        
        // Migrate existing meta.json to per-version layout
        this.migrateExistingMetaToPerVersion(prefix);
        
        this.updateByTgz(prefix);
        // Generate meta.json from per-version files
        new PerVersionLayout(this.asto).generateMetaJson(prefix)
            .thenCompose(meta -> this.asto.save(
                new Key.From(prefix, "meta.json"),
                new com.auto1.pantera.asto.Content.From(meta.toString().getBytes(StandardCharsets.UTF_8))
            ))
            .toCompletableFuture()
            .join();
        MatcherAssert.assertThat(
            new JsonFromMeta(this.asto, prefix).json().getJsonObject("versions").keySet(),
            Matchers.containsInAnyOrder("1.0.1", "1.0.2")
        );
    }

    @Test
    void metaContainsDistFields() {
        final Key prefix = new Key.From("@hello/simple-npm-project");
        this.updateByTgz(prefix);
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
                .getJsonObject("1.0.2")
                .getJsonObject("dist")
                .keySet(),
            Matchers.containsInAnyOrder("integrity", "shasum", "tarball")
        );
    }

    @Test
    void containsCorrectLatestDistTag() {
        final Key prefix = new Key.From("@hello/simple-npm-project");
        new TestResource("storage/@hello/simple-npm-project/meta.json")
            .saveTo(this.asto, new Key.From(prefix, "meta.json"));
        
        // Migrate existing meta.json to per-version layout
        this.migrateExistingMetaToPerVersion(prefix);
        
        this.updateByTgz(prefix);
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
                .getJsonObject("dist-tags")
                .getString("latest"),
            new IsEqual<>("1.0.2")
        );
    }

    private void updateByTgz(final Key prefix) {
        new MetaUpdate.ByTgz(
            new TgzArchive(
                new String(
                    new TestResource("binaries/simple-npm-project-1.0.2.tgz").asBytes(),
                    StandardCharsets.ISO_8859_1
                ), false
            )
        ).update(new Key.From(prefix), this.asto)
            .join();
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
            final javax.json.JsonObject versions = meta.getJsonObject("versions");
            final PerVersionLayout layout = new PerVersionLayout(this.asto);
            
            for (String version : versions.keySet()) {
                final javax.json.JsonObject versionData = versions.getJsonObject(version);
                layout.addVersion(prefix, version, versionData)
                    .toCompletableFuture()
                    .join();
            }
        }
    }
}
