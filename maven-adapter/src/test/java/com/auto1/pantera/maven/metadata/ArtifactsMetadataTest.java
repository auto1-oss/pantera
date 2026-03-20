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
package com.auto1.pantera.maven.metadata;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.maven.MetadataXml;
import java.util.concurrent.CompletionException;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link ArtifactsMetadata}.
 * @since 0.5
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ArtifactsMetadataTest {

    /**
     * Test storage.
     */
    private Storage storage;

    /**
     * Test key.
     */
    private Key key;

    @BeforeEach
    void initiate() {
        this.storage = new InMemoryStorage();
        this.key = new Key.From("com/test/logger");
    }

    @Test
    void readsMaxVersion() {
        final String expected = "1.10-SNAPSHOT";
        this.generate("0.3", expected, "1.0.1", "0.9-SNAPSHOT", "0.22");
        MatcherAssert.assertThat(
            new ArtifactsMetadata(this.storage).maxVersion(this.key).toCompletableFuture().join(),
            new IsEqual<>(expected)
        );
    }

    @Test
    void readsVersion() {
        final String expected = "1.0";
        this.generate(expected, "0.9");
        MatcherAssert.assertThat(
            new ArtifactsMetadata(this.storage).maxVersion(this.key).toCompletableFuture().join(),
            new IsEqual<>(expected)
        );
    }

    @Test
    void throwsExceptionOnInvalidMetadata() {
        this.generate();
        MatcherAssert.assertThat(
            Assertions.assertThrows(
                CompletionException.class,
                () -> new ArtifactsMetadata(this.storage)
                    .maxVersion(this.key).toCompletableFuture().join()
            ).getCause(),
            new IsInstanceOf(IllegalArgumentException.class)
        );
    }

    @Test
    void readsGroupAndArtifactIds() {
        this.generate("8.0");
        MatcherAssert.assertThat(
            new ArtifactsMetadata(this.storage).groupAndArtifact(this.key)
                .toCompletableFuture().join(),
            new IsEqual<>(new ImmutablePair<>("com.test", "logger"))
        );
    }

    /**
     * Generates maven-metadata.xml.
     * @param versions Versions list
     */
    private void generate(final String... versions) {
        new MetadataXml("com.test", "logger").addXmlToStorage(
            this.storage, new Key.From(this.key, "maven-metadata.xml"),
            new MetadataXml.VersionTags(versions)
        );
    }

}
