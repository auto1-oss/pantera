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
package com.auto1.pantera.scheduling;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link FileVersionDetector}.
 *
 * @since 2.1.0
 */
class FileVersionDetectorTest {

    @ParameterizedTest
    @CsvSource({
        // Maven-like path: group.artifact.version.filename
        "wkda.services.car-purchase-blocker-service.1.5.0-SNAPSHOT.car-purchase-blocker-service-1.5.0-20191106.123915-7.pom, 1.5.0-SNAPSHOT",
        // Simple version directory
        "reports.2024.q1.pdf, 2024",
        // Tarball with hyphen-prefix version
        "elinks-current-0.11.tar.gz, 0.11",
        // Nginx-style
        "nginx-1.24.0.tar.gz, 1.24.0",
        // Package with beta suffix
        "package-0.0.1-beta.tar.gz, 0.0.1-beta",
        // v-prefixed version
        "v1.0.0.app.tar.gz, v1.0.0",
        // Spring-like deep path
        "org.springframework.boot.2.7.0.spring-boot-2.7.0.jar, 2.7.0",
    })
    void detectsVersionFromDottedName(final String name, final String expected) {
        MatcherAssert.assertThat(
            String.format("detect('%s')", name),
            FileVersionDetector.detect(name),
            new IsEqual<>(expected)
        );
    }

    @ParameterizedTest
    @CsvSource({
        // No version tokens at all
        "config.application.yml",
        // Single segment
        "readme",
    })
    void returnsUnknownWhenNoVersion(final String name) {
        MatcherAssert.assertThat(
            String.format("detect('%s') should be UNKNOWN", name),
            FileVersionDetector.detect(name),
            new IsEqual<>(FileVersionDetector.UNKNOWN)
        );
    }

    @Test
    void nullReturnsUnknown() {
        MatcherAssert.assertThat(
            FileVersionDetector.detect(null),
            new IsEqual<>(FileVersionDetector.UNKNOWN)
        );
    }

    @Test
    void emptyReturnsUnknown() {
        MatcherAssert.assertThat(
            FileVersionDetector.detect(""),
            new IsEqual<>(FileVersionDetector.UNKNOWN)
        );
    }

    @Test
    void isVersionTokenDigitStart() {
        MatcherAssert.assertThat(
            FileVersionDetector.isVersionToken("1"),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            FileVersionDetector.isVersionToken("0-SNAPSHOT"),
            new IsEqual<>(true)
        );
    }

    @Test
    void isVersionTokenVPrefix() {
        MatcherAssert.assertThat(
            FileVersionDetector.isVersionToken("v2"),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "bare v is not a version",
            FileVersionDetector.isVersionToken("v"),
            new IsEqual<>(false)
        );
    }

    @Test
    void isVersionTokenRejectsLetters() {
        MatcherAssert.assertThat(
            FileVersionDetector.isVersionToken("spring-boot"),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            FileVersionDetector.isVersionToken(""),
            new IsEqual<>(false)
        );
    }
}
