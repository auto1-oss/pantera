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

import com.auto1.pantera.http.Headers;
import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the {@code client_base_scheme} setting.
 *
 * <p>Behind a TLS-terminating layer-4 load balancer Pantera receives neither
 * a TLS connection of its own nor {@code X-Forwarded-Proto}, so there is no
 * signal to derive a scheme from and every emitted link says {@code http}.
 * This setting forces the scheme while leaving the host derived per request,
 * so several client-facing DNS names each keep their own URLs — which is what
 * distinguishes it from the canonical base URL, which pins both together.</p>
 *
 * @since 2.2.8
 */
final class ClientBaseSchemeTest {

    @AfterEach
    void tearDown() {
        ClientBaseUrlSettingsRegistry.uninstall();
    }

    @Test
    @DisplayName("https is forced while the host still derives per request")
    void forcedHttpsKeepsPerHostDerivation() {
        install("https");
        MatcherAssert.assertThat(
            "first DNS name keeps its own host",
            new ClientBaseUrl(Headers.from("Host", "packages.example.test")).origin(),
            new IsEqual<>("https://packages.example.test")
        );
        MatcherAssert.assertThat(
            "second DNS name keeps its own host",
            new ClientBaseUrl(Headers.from("Host", "artifactory.example.test")).origin(),
            new IsEqual<>("https://artifactory.example.test")
        );
    }

    @Test
    @DisplayName("auto keeps deriving from the request, i.e. http without a forwarded proto")
    void autoDerivesFromTheRequest() {
        install(ClientBaseUrlSettings.SCHEME_AUTO);
        MatcherAssert.assertThat(
            "no X-Forwarded-Proto and no TLS means http — the pre-setting behaviour",
            new ClientBaseUrl(Headers.from("Host", "packages.example.test")).origin(),
            new IsEqual<>("http://packages.example.test")
        );
    }

    @Test
    @DisplayName("A forced scheme wins over X-Forwarded-Proto")
    void forcedSchemeBeatsForwardedProto() {
        ClientBaseUrlSettingsRegistry.install(
            () -> new ClientBaseUrlSettings(true, List.of(), "", "https")
        );
        MatcherAssert.assertThat(
            "the setting is authoritative, not merely a default",
            new ClientBaseUrl(
                Headers.from(
                    java.util.Map.entry("Host", "packages.example.test"),
                    java.util.Map.entry("X-Forwarded-Proto", "http")
                )
            ).origin(),
            new IsEqual<>("https://packages.example.test")
        );
    }

    @Test
    @DisplayName("An unknown scheme is rejected rather than silently ignored")
    void unknownSchemeRejected() {
        try {
            new ClientBaseUrlSettings(false, List.of(), "", "ftp");
            MatcherAssert.assertThat("expected rejection", false, new IsEqual<>(true));
        } catch (final IllegalArgumentException expected) {
            MatcherAssert.assertThat(
                "the message names the offending value",
                expected.getMessage().contains("ftp"), new IsEqual<>(true)
            );
        }
    }

    @Test
    @DisplayName("Blank and mixed-case values normalise")
    void blankAndCaseNormalise() {
        MatcherAssert.assertThat(
            "blank means auto",
            new ClientBaseUrlSettings(false, List.of(), "", "  ").clientBaseScheme(),
            new IsEqual<>(ClientBaseUrlSettings.SCHEME_AUTO)
        );
        MatcherAssert.assertThat(
            "case is normalised",
            new ClientBaseUrlSettings(false, List.of(), "", "HTTPS").clientBaseScheme(),
            new IsEqual<>("https")
        );
    }

    private static void install(final String scheme) {
        ClientBaseUrlSettingsRegistry.install(
            () -> new ClientBaseUrlSettings(false, List.of(), "", scheme)
        );
    }
}
