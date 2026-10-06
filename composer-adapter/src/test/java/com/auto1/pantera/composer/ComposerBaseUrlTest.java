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

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.ClientBaseUrl;
import com.auto1.pantera.http.headers.ClientBaseUrlSettings;
import com.auto1.pantera.http.headers.ClientBaseUrlSettingsRegistry;
import java.util.List;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link ComposerBaseUrl}.
 */
final class ComposerBaseUrlTest {

    @ParameterizedTest
    @CsvSource({
        "http://h:8080,http://h:8080/php-api",
        "http://h:8080/,http://h:8080/php-api",
        "http://h/artifactory,http://h/artifactory/php-api",
        "http://h/artifactory/php-api,http://h/artifactory/php-api",
        "http://h/artifactory/php-api/,http://h/artifactory/php-api"
    })
    void configuredUrlEndsWithTheRepository(final String configured, final String expected) {
        MatcherAssert.assertThat(
            new ComposerBaseUrl(Optional.of(configured), "/php-api/").resolve(Headers.EMPTY),
            new IsEqual<>(expected)
        );
    }

    @Test
    void stampedGroupBaseIsTakenAsIs() {
        MatcherAssert.assertThat(
            new ComposerBaseUrl(Optional.of("http://legacy.example.com/php-api"), "php-api")
                .resolve(Headers.from(ClientBaseUrl.HEADER, "https://packages.example.com/php_group/")),
            new IsEqual<>("https://packages.example.com/php_group")
        );
    }

    @ParameterizedTest
    @CsvSource({
        "http://h/artifactory,http://h/artifactory,http://h/artifactory/php-api",
        "http://h/artifactory,http://h/artifactory/,http://h/artifactory/php-api",
        "http://h/artifactory/,http://h/artifactory,http://h/artifactory/php-api",
        "http://h/artifactory/php-api,http://h/artifactory/php-api,http://h/artifactory/php-api"
    })
    void stampOfTheOwnConfiguredUrlGetsTheRepositorySegment(
        final String configured, final String stamped, final String expected
    ) {
        // SliceByPath stamps the raw url: of the addressed repository; when
        // that is this repository, the stamp must get the same normalisation
        // the configured value gets, or a url: without the repository
        // segment yields links that 404 (it worked before 2.2.10).
        MatcherAssert.assertThat(
            new ComposerBaseUrl(Optional.of(configured), "php-api")
                .resolve(Headers.from(ClientBaseUrl.HEADER, stamped)),
            new IsEqual<>(expected)
        );
    }

    @Test
    void requestOriginHonoursTheCanonicalBaseUrl() {
        ClientBaseUrlSettingsRegistry.install(
            () -> new ClientBaseUrlSettings(false, List.of(), "https://pkgs.example.com/artifactory")
        );
        try {
            MatcherAssert.assertThat(
                new ComposerBaseUrl(Optional.empty(), "php-api")
                    .resolve(new Headers().add("Host", "internal:8080")),
                new IsEqual<>("https://pkgs.example.com/artifactory/php-api")
            );
        } finally {
            ClientBaseUrlSettingsRegistry.uninstall();
        }
    }

    @Test
    void requestOriginPlusRepositoryWithoutUrlOrStamp() {
        MatcherAssert.assertThat(
            new ComposerBaseUrl(Optional.empty(), "php-api")
                .resolve(new Headers().add("Host", "packages.example.com")),
            new IsEqual<>("http://packages.example.com/php-api")
        );
    }
}
