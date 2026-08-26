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
package com.auto1.pantera.http.headers;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

final class ClientBaseUrlSettingsTest {

    @Test
    void defaultsAreNotTrustedAndPermissive() {
        final ClientBaseUrlSettings defaults = ClientBaseUrlSettings.defaults();
        MatcherAssert.assertThat(
            "forwarded headers are not trusted by default",
            defaults.trustForwardedHeaders(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "an empty allowlist is the default (permissive)",
            defaults.hostAllowlist(), new IsEqual<>(List.of())
        );
        MatcherAssert.assertThat(
            "the canonical base URL is unset by default",
            defaults.canonicalBaseUrl(), new IsEqual<>("")
        );
    }

    @Test
    void twoArgConstructorLeavesCanonicalBaseUrlUnset() {
        MatcherAssert.assertThat(
            new ClientBaseUrlSettings(true, List.of("a.example.com")).canonicalBaseUrl(),
            new IsEqual<>("")
        );
    }

    @Test
    void blankCanonicalBaseUrlIsNormalizedToUnset() {
        MatcherAssert.assertThat(
            new ClientBaseUrlSettings(false, List.of(), "   ").canonicalBaseUrl(),
            new IsEqual<>("")
        );
    }

    @Test
    void canonicalBaseUrlTrailingSlashIsStrippedSoConcatenationNeverDoublesIt() {
        MatcherAssert.assertThat(
            new ClientBaseUrlSettings(false, List.of(), "http://localhost:9999/").canonicalBaseUrl(),
            new IsEqual<>("http://localhost:9999")
        );
    }

    @Test
    void canonicalBaseUrlWithPathPrefixTrailingSlashIsStripped() {
        MatcherAssert.assertThat(
            new ClientBaseUrlSettings(
                false, List.of(), "https://reg.example.com/artifactory/"
            ).canonicalBaseUrl(),
            new IsEqual<>("https://reg.example.com/artifactory")
        );
    }

    @Test
    void rejectsACanonicalBaseUrlThatDoesNotParseAsAUri() {
        final IllegalArgumentException ex = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new ClientBaseUrlSettings(false, List.of(), "not a url at all ::")
        );
        MatcherAssert.assertThat(ex.getMessage(), new StringContains("canonicalBaseUrl"));
    }

    @Test
    void rejectsACanonicalBaseUrlWithANonHttpScheme() {
        final IllegalArgumentException ex = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new ClientBaseUrlSettings(false, List.of(), "ftp://reg.example.com")
        );
        MatcherAssert.assertThat(ex.getMessage(), new StringContains("http"));
    }

    @Test
    void rejectsACanonicalBaseUrlWithNoHost() {
        final IllegalArgumentException ex = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new ClientBaseUrlSettings(false, List.of(), "http:///no-host")
        );
        MatcherAssert.assertThat(ex.getMessage(), new StringContains("host"));
    }

    @Test
    void rejectsABlankAllowlistEntry() {
        final IllegalArgumentException ex = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new ClientBaseUrlSettings(false, List.of("good.example.com", " "))
        );
        MatcherAssert.assertThat(ex.getMessage(), new StringContains("blank"));
    }

    @Test
    void copiesTheAllowlistDefensively() {
        final ArrayList<String> mutable = new ArrayList<>();
        mutable.add("a.example.com");
        final ClientBaseUrlSettings settings = new ClientBaseUrlSettings(false, mutable);
        mutable.add("b.example.com");
        MatcherAssert.assertThat(settings.hostAllowlist(), new IsEqual<>(List.of("a.example.com")));
    }
}
