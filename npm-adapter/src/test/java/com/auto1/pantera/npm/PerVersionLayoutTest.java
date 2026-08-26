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
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for the durable dist-tags sidecar added to {@link PerVersionLayout} —
 * the fix for the meta.json split-brain (spec WS4-npm.3): dist-tag
 * add/remove/merge must be readable back through {@link PerVersionLayout},
 * and {@link PerVersionLayout#generateMetaJson(Key)} must merge the sidecar
 * over the computed {@code latest} while still falling back to the computed
 * value when the sidecar is absent (backward compatibility with packages
 * published before the sidecar existed).
 */
final class PerVersionLayoutTest {

    /**
     * Test package key.
     */
    private static final Key PKG = new Key.From("@hello/simple-npm-project");

    /**
     * Storage under test.
     */
    private Storage storage;

    /**
     * Layout under test.
     */
    private PerVersionLayout layout;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.layout = new PerVersionLayout(this.storage);
    }

    @Test
    void generateMetaJsonFallsBackToComputedLatestWhenSidecarAbsent() {
        this.addVersion("1.0.0");
        this.addVersion("2.0.0");
        final JsonObject meta = this.metaJson();
        MatcherAssert.assertThat(
            "No sidecar written yet — latest falls back to the highest semver version",
            meta.getJsonObject("dist-tags").getString("latest"),
            new IsEqual<>("2.0.0")
        );
    }

    @Test
    void mergeDistTagsPersistsCustomTagWithoutTouchingLatest() {
        this.addVersion("1.0.0");
        this.layout.mergeDistTags(
            PerVersionLayoutTest.PKG,
            Json.createObjectBuilder().add("latest", "1.0.0").build()
        ).toCompletableFuture().join();
        this.addVersion("2.0.0-beta.1");
        // A "npm publish --tag next" only ever sends the "next" tag — latest
        // must be left exactly as the client last set it.
        this.layout.mergeDistTags(
            PerVersionLayoutTest.PKG,
            Json.createObjectBuilder().add("next", "2.0.0-beta.1").build()
        ).toCompletableFuture().join();
        final JsonObject tags = this.layout.readDistTags(PerVersionLayoutTest.PKG)
            .toCompletableFuture().join();
        MatcherAssert.assertThat(
            "latest is untouched by the --tag next publish",
            tags.getString("latest"),
            new IsEqual<>("1.0.0")
        );
        MatcherAssert.assertThat(
            "next reflects the prerelease published under --tag next",
            tags.getString("next"),
            new IsEqual<>("2.0.0-beta.1")
        );
    }

    @Test
    void writeTagThenRemoveTagRoundTrips() {
        this.layout.writeTag(PerVersionLayoutTest.PKG, "beta", "1.0.0")
            .toCompletableFuture().join();
        MatcherAssert.assertThat(
            "Tag was written",
            this.layout.readDistTags(PerVersionLayoutTest.PKG)
                .toCompletableFuture().join().getString("beta"),
            new IsEqual<>("1.0.0")
        );
        this.layout.removeTag(PerVersionLayoutTest.PKG, "beta")
            .toCompletableFuture().join();
        MatcherAssert.assertThat(
            "Tag was removed",
            this.layout.readDistTags(PerVersionLayoutTest.PKG)
                .toCompletableFuture().join().containsKey("beta"),
            new IsEqual<>(false)
        );
    }

    @Test
    void removeTagsPointingAtDropsEveryMatchingTagButKeepsOthers() {
        this.layout.writeTag(PerVersionLayoutTest.PKG, "latest", "1.0.0")
            .toCompletableFuture().join();
        this.layout.writeTag(PerVersionLayoutTest.PKG, "stable", "1.0.0")
            .toCompletableFuture().join();
        this.layout.writeTag(PerVersionLayoutTest.PKG, "beta", "2.0.0-beta.1")
            .toCompletableFuture().join();
        this.layout.removeTagsPointingAt(PerVersionLayoutTest.PKG, "1.0.0")
            .toCompletableFuture().join();
        final JsonObject tags = this.layout.readDistTags(PerVersionLayoutTest.PKG)
            .toCompletableFuture().join();
        MatcherAssert.assertThat(
            "latest (pointed at the removed version) is dropped",
            tags.containsKey("latest"),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "stable (pointed at the removed version) is dropped",
            tags.containsKey("stable"),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "beta (pointed at a different version) survives",
            tags.getString("beta"),
            new IsEqual<>("2.0.0-beta.1")
        );
    }

    @Test
    void listVersionsReadsActualVersionFieldNotSanitizedFilename() {
        // Build metadata ("+build.5") is stripped by filename sanitization —
        // listVersions must read the real version from file content instead.
        this.addVersion("1.0.0+build.5");
        final boolean present = this.layout.listVersions(PerVersionLayoutTest.PKG)
            .toCompletableFuture().join()
            .contains("1.0.0+build.5");
        MatcherAssert.assertThat(
            "Actual (unsanitized) version string is recovered from file content",
            present,
            new IsEqual<>(true)
        );
    }

    @Test
    void deleteVersionRemovesItFromGeneratedMeta() {
        this.addVersion("1.0.0");
        this.addVersion("1.0.1");
        this.layout.deleteVersion(PerVersionLayoutTest.PKG, "1.0.1")
            .toCompletableFuture().join();
        final JsonObject meta = this.metaJson();
        MatcherAssert.assertThat(
            "Deleted version no longer appears in the generated packument",
            meta.getJsonObject("versions").containsKey("1.0.1"),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "Latest recomputes to the remaining highest version",
            meta.getJsonObject("dist-tags").getString("latest"),
            new IsEqual<>("1.0.0")
        );
    }

    /**
     * Publish a bare version file (name comes from the fixed test package).
     * @param version Version to publish
     */
    private void addVersion(final String version) {
        this.layout.addVersion(
            PerVersionLayoutTest.PKG, version,
            Json.createObjectBuilder()
                .add("name", "@hello/simple-npm-project")
                .add("version", version)
                .build()
        ).toCompletableFuture().join();
    }

    /**
     * Convenience: generate the aggregated meta.json for the fixed test package.
     * @return Generated meta.json
     */
    private JsonObject metaJson() {
        return this.layout.generateMetaJson(PerVersionLayoutTest.PKG)
            .toCompletableFuture().join();
    }
}
