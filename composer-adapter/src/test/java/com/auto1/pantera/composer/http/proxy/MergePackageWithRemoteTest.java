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
package com.auto1.pantera.composer.http.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.test.TestResource;
import org.cactoos.set.SetOf;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.json.JsonObject;
import java.util.Optional;

/**
 * Test for {@link MergePackage.WithRemote}.
 * @since 0.4
 */
final class MergePackageWithRemoteTest {
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"packages\":{}}"})
    void returnsEmptyWhenLocalAndRemoteNotContainPackage(final String content) {
        final byte[] pkgs = content.getBytes();
        MatcherAssert.assertThat(
            this.mergedContent("not/exist", pkgs, pkgs).isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void returnsFromRemoteForEmptyLocal() {
        final String name = "psr/log";
        final byte[] remote = new TestResource("merge/remote.json").asBytes();
        final JsonObject pkgs = this.packagesFromMerged("{}".getBytes(), remote);
        MatcherAssert.assertThat(
            "Contains required package name",
            pkgs.keySet(),
            new IsEqual<>(new SetOf<>(name))
        );
        MatcherAssert.assertThat(
            "Contains all versions",
            pkgs.getJsonObject(name).keySet().toArray(),
            Matchers.arrayContainingInAnyOrder("1.1.2", "1.1.3")
        );
    }

    @Test
    void emptyWhenRemoteDoesNotContainsNameAndLocalIsEmpty() {
        final byte[] remote = new TestResource("merge/remote.json").asBytes();
        MatcherAssert.assertThat(
            this.mergedContent("not/exist", "{}".getBytes(), remote).isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void mergesLocalWithRemote() {
        final String name = "psr/log";
        final byte[] remote = new TestResource("merge/remote.json").asBytes();
        final byte[] local = new TestResource("merge/local.json").asBytes();
        final JsonObject pkgs = this.packagesFromMerged(local, remote);
        MatcherAssert.assertThat(
            "Contains required package name",
            pkgs.keySet(),
            new IsEqual<>(new SetOf<>(name))
        );
        MatcherAssert.assertThat(
            "Contains all versions",
            pkgs.getJsonObject(name).keySet(),
            Matchers.containsInAnyOrder("1.1.3", "1.1.4", "1.1.2")
        );
        for (final String vrsn: pkgs.getJsonObject(name).keySet()) {
            MatcherAssert.assertThat(
                "Each entry contains required fields",
                pkgs.getJsonObject(name)
                    .getJsonObject(vrsn)
                    .keySet(),
                Matchers.hasItems("version", "name", "dist", "uid")
            );
        }
    }

    @Test
    void returnsFromLocalForEmptyRemote() {
        final String name = "psr/log";
        final byte[] local = new TestResource("merge/local.json").asBytes();
        MatcherAssert.assertThat(
            this.packagesFromMerged(local, "{}".getBytes()).keySet(),
            new IsEqual<>(new SetOf<>(name))
        );
    }

    @Test
    void returnsEmptyWhenLocalAndRemoteContainAnotherPackage() {
        final byte[] local = new TestResource("merge/local.json").asBytes();
        final byte[] remote = new TestResource("merge/remote.json").asBytes();
        MatcherAssert.assertThat(
            this.mergedContent("not/exist", local, remote).isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void expandsMinifiedRemoteVersions() {
        // merge/remote.json is packagist's composer/2.0 minified form: 1.1.2
        // carries only what changed, so it inherits name and description.
        final JsonObject versions = this.packagesFromMerged(
            "{}".getBytes(), new TestResource("merge/remote.json").asBytes()
        ).getJsonObject("psr/log");
        MatcherAssert.assertThat(
            "1.1.2 inherits the description of 1.1.3",
            versions.getJsonObject("1.1.2").getString("description", ""),
            new IsEqual<>("Common interface for logging libraries")
        );
        MatcherAssert.assertThat(
            "1.1.2 keeps its own dist",
            versions.getJsonObject("1.1.2").getJsonObject("dist").getString("reference"),
            new IsEqual<>("446d54b4cb6bf489fc9d75f55843658e6f25d801")
        );
    }

    @Test
    void honoursUnsetInMinifiedRemote() {
        final byte[] remote = (
            "{\"minified\":\"composer/2.0\",\"packages\":{\"psr/log\":["
                + "{\"name\":\"psr/log\",\"version\":\"2.0.0\",\"require\":{\"php\":\">=8\"}},"
                + "{\"version\":\"1.0.0\",\"require\":\"__unset\"}]}}"
        ).getBytes();
        final JsonObject versions = this.packagesFromMerged("{}".getBytes(), remote)
            .getJsonObject("psr/log");
        MatcherAssert.assertThat(
            "__unset removes the inherited require",
            versions.getJsonObject("1.0.0").containsKey("require"), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the newest entry keeps its require",
            versions.getJsonObject("2.0.0").containsKey("require"), new IsEqual<>(true)
        );
    }

    private Optional<Content> mergedContent(
        final String name, final byte[] local, final byte[] remote
    ) {
        return new MergePackage.WithRemote(name, new Content.From(local))
            .merge(
                Optional.of(new Content.From(remote))
            ).toCompletableFuture().join();
    }

    private JsonObject packagesFromMerged(final byte[] local, final byte[] remote) {
        return this.mergedContent("psr/log", local, remote)
            .orElseThrow().asJsonObject()
            .getJsonObject("packages");
    }
}
