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
