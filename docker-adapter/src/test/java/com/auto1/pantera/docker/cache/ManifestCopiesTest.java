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

import com.auto1.pantera.docker.ManifestReference;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Copies of manifests are made at once, never, or once the request they
 * were read for has been answered.
 *
 * @since 2.2.15
 */
final class ManifestCopiesTest {

    @Test
    void holdsCopiesUntilTheAnswerServesTheVersion() {
        final List<String> made = new CopyOnWriteArrayList<>();
        final ManifestCopies copies = new ManifestCopies();
        copies.submit(() -> made.add("tag"));
        copies.submit(() -> made.add("platform"));
        MatcherAssert.assertThat(
            "nothing is copied before the answer is known",
            made, new IsEqual<>(List.of())
        );
        copies.commit();
        copies.commit();
        MatcherAssert.assertThat(
            "every copy held is made once when the answer serves the version",
            made, new IsEqual<>(List.of("tag", "platform"))
        );
        copies.submit(() -> made.add("late"));
        MatcherAssert.assertThat(
            "a copy handed over afterwards is made at once",
            made, new IsEqual<>(List.of("tag", "platform", "late"))
        );
    }

    @Test
    void dropsCopiesWhenTheAnswerRefusesTheVersion() {
        final List<String> made = new CopyOnWriteArrayList<>();
        final ManifestCopies copies = new ManifestCopies();
        copies.submit(() -> made.add("tag"));
        copies.discard();
        copies.commit();
        copies.submit(() -> made.add("late"));
        MatcherAssert.assertThat(
            made, new IsEqual<>(List.of())
        );
    }

    @Test
    void copyThatFailsToStartDoesNotStopTheOthers() {
        final List<String> made = new CopyOnWriteArrayList<>();
        final ManifestCopies copies = new ManifestCopies();
        copies.submit(
            () -> {
                throw new IllegalStateException("storage refused");
            }
        );
        copies.submit(() -> made.add("platform"));
        copies.commit();
        MatcherAssert.assertThat(
            made, new IsEqual<>(List.of("platform"))
        );
    }

    @Test
    void copiesUnderAVerdictStartAtOnceAndStoreWhatItAllows() {
        final List<String> made = new CopyOnWriteArrayList<>();
        final ManifestCopies copies = new ManifestCopies(
            (image, ref, manifest) -> CompletableFuture.completedFuture(
                "allowed".equals(ref.digest())
            )
        );
        copies.submit(() -> made.add("head"));
        MatcherAssert.assertThat(
            "a copy under a verdict starts at once",
            made, new IsEqual<>(List.of("head"))
        );
        MatcherAssert.assertThat(
            "and stores what the verdict allows",
            copies.stores("image", ManifestReference.from("allowed"), null)
                .toCompletableFuture().join(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "but not what it refuses",
            copies.stores("image", ManifestReference.from("refused"), null)
                .toCompletableFuture().join(),
            new IsEqual<>(false)
        );
    }

    @Test
    void verdictThatFailsAllowsAsCooldownFailsOpen() {
        final ManifestCopies copies = new ManifestCopies(
            (image, ref, manifest) -> CompletableFuture.failedFuture(
                new IllegalStateException("db down")
            )
        );
        MatcherAssert.assertThat(
            copies.stores("image", ManifestReference.from("1"), null)
                .toCompletableFuture().join(),
            new IsEqual<>(true)
        );
    }

    @Test
    void decidedCopiesStoreWithoutAVerdict() {
        MatcherAssert.assertThat(
            new ManifestCopies().stores("image", ManifestReference.from("1"), null)
                .toCompletableFuture().join(),
            new IsEqual<>(true)
        );
    }

    @Test
    void repositoryViewCopiesAtOnceAndReadOnlyViewNever() {
        final List<String> made = new CopyOnWriteArrayList<>();
        ManifestCopies.AT_ONCE.submit(() -> made.add("client"));
        ManifestCopies.NEVER.submit(() -> made.add("replay"));
        MatcherAssert.assertThat(
            made, new IsEqual<>(List.of("client"))
        );
    }
}
