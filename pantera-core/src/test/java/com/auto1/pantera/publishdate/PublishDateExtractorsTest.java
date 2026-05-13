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
package com.auto1.pantera.publishdate;

import com.auto1.pantera.http.Headers;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

/**
 * Track 5 Phase 3B SPI tests.
 *
 * @since 2.2.0
 */
final class PublishDateExtractorsTest {

    @AfterEach
    void cleanRegistry() {
        // Test isolation: Surefire shares the JVM, so leaking a registration
        // across tests can mask absence-of-fallback contracts.
        PublishDateExtractors.instance().clear();
    }

    @Test
    @DisplayName("unregistered repo-type returns a NO_OP extractor that yields empty")
    void unregisteredYieldsEmpty() {
        final PublishDateExtractor extractor =
            PublishDateExtractors.instance().forRepoType("conda");
        MatcherAssert.assertThat(
            "no-op extractor is non-null",
            extractor != null, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "no-op extractor returns empty",
            extractor.extract(Headers.EMPTY, "foo", "1.0"),
            new IsEqual<>(Optional.<Instant>empty())
        );
    }

    @Test
    @DisplayName("registered extractor is returned by forRepoType")
    void registeredExtractorReturned() {
        final Instant fixed = Instant.parse("2026-05-12T10:00:00Z");
        PublishDateExtractors.instance().register(
            "fake", (headers, name, version) -> Optional.of(fixed)
        );
        final PublishDateExtractor extractor =
            PublishDateExtractors.instance().forRepoType("fake");
        MatcherAssert.assertThat(
            "registered extractor returned",
            extractor.extract(Headers.EMPTY, "foo", "1.0"),
            new IsEqual<>(Optional.of(fixed))
        );
    }

    @Test
    @DisplayName("re-registration replaces the previous extractor")
    void reRegistrationReplaces() {
        final Instant first = Instant.parse("2020-01-01T00:00:00Z");
        final Instant second = Instant.parse("2026-05-12T00:00:00Z");
        PublishDateExtractors.instance().register(
            "fake", (h, n, v) -> Optional.of(first)
        );
        PublishDateExtractors.instance().register(
            "fake", (h, n, v) -> Optional.of(second)
        );
        MatcherAssert.assertThat(
            "second registration replaces first",
            PublishDateExtractors.instance()
                .forRepoType("fake").extract(Headers.EMPTY, "x", "1"),
            new IsEqual<>(Optional.of(second))
        );
    }

    @Test
    @DisplayName("null repoType / extractor are rejected with IllegalArgumentException")
    void nullArgumentsRejected() {
        boolean threwOnNullType = false;
        try {
            PublishDateExtractors.instance().register(
                null, (h, n, v) -> Optional.empty()
            );
        } catch (final IllegalArgumentException ex) {
            threwOnNullType = true;
        }
        MatcherAssert.assertThat(
            "null repoType rejected", threwOnNullType, new IsEqual<>(true)
        );
        boolean threwOnNullExtractor = false;
        try {
            PublishDateExtractors.instance().register("maven", null);
        } catch (final IllegalArgumentException ex) {
            threwOnNullExtractor = true;
        }
        MatcherAssert.assertThat(
            "null extractor rejected", threwOnNullExtractor, new IsEqual<>(true)
        );
    }
}
