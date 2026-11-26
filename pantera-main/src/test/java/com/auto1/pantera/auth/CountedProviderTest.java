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
package com.auto1.pantera.auth;

import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthRefusal;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.PasswordCheck;
import com.auto1.pantera.metrics.MicrometerMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CountedProvider}: every password check that leaves
 * Pantera for an identity provider is counted, by provider and outcome.
 *
 * @since 2.2.15
 */
final class CountedProviderTest {

    @BeforeAll
    static void metrics() {
        MicrometerMetrics.initialize(new SimpleMeterRegistry());
    }

    @Test
    void acceptedCheckIsCounted() {
        final double before = CountedProviderTest.checks("okta", "accepted");
        new PasswordCheck(
            new CountedProvider((name, pass) -> Optional.of(new AuthUser(name, "okta")), "okta")
        ).now("alice", "secret");
        MatcherAssert.assertThat(
            CountedProviderTest.checks("okta", "accepted") - before, new IsEqual<>(1.0)
        );
    }

    @Test
    void wrongPasswordIsCountedAsRejected() {
        final double before = CountedProviderTest.checks("okta", "rejected");
        new PasswordCheck(new CountedProvider((name, pass) -> Optional.empty(), "okta"))
            .now("alice", "wrong");
        MatcherAssert.assertThat(
            CountedProviderTest.checks("okta", "rejected") - before, new IsEqual<>(1.0)
        );
    }

    @Test
    void refusalOfTheProviderIsCountedAsRefused() {
        final double before = CountedProviderTest.checks("okta", "refused");
        new PasswordCheck(
            new CountedProvider(
                (name, pass) -> {
                    new AuthRefusal("mfa_required").note();
                    return Optional.empty();
                },
                "okta"
            )
        ).now("alice", "secret");
        MatcherAssert.assertThat(
            CountedProviderTest.checks("okta", "refused") - before, new IsEqual<>(1.0)
        );
    }

    @Test
    void outageIsCountedAsUnavailable() {
        final double before = CountedProviderTest.checks("keycloak", "unavailable");
        new PasswordCheck(
            new CountedProvider(
                (name, pass) -> {
                    new AuthRefusal(
                        "identity_provider_unavailable", RsStatus.SERVICE_UNAVAILABLE, 0L, "down"
                    ).note();
                    return Optional.empty();
                },
                "keycloak"
            )
        ).now("alice", "secret");
        MatcherAssert.assertThat(
            CountedProviderTest.checks("keycloak", "unavailable") - before, new IsEqual<>(1.0)
        );
    }

    @Test
    void failureOfTheProviderCodeIsCountedAsUnavailableAndRethrown() {
        final double before = CountedProviderTest.checks("github", "unavailable");
        final Authentication broken = new CountedProvider(
            (name, pass) -> {
                throw new IllegalStateException("provider failed");
            },
            "github"
        );
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, () -> broken.user("alice", "secret")
        );
        MatcherAssert.assertThat(
            CountedProviderTest.checks("github", "unavailable") - before, new IsEqual<>(1.0)
        );
    }

    @Test
    void wrapperKeepsWhatTheProviderSaysAboutUserNames() {
        final Authentication origin = new Authentication() {
            @Override
            public Optional<AuthUser> user(final String name, final String pass) {
                return Optional.empty();
            }

            @Override
            public boolean canHandle(final String name) {
                return name.endsWith("@example.com");
            }

            @Override
            public Collection<String> userDomains() {
                return List.of("@example.com");
            }

            @Override
            public boolean isAuthoritative(final String name) {
                return "owner@example.com".equals(name);
            }
        };
        final Authentication counted = new CountedProvider(origin, "okta");
        MatcherAssert.assertThat(
            "domains, routing and authority are the provider's",
            List.of(
                counted.canHandle("a@example.com"), counted.canHandle("a@other.org"),
                counted.userDomains().size() == 1, counted.isAuthoritative("owner@example.com")
            ),
            new IsEqual<>(List.of(true, false, true, true))
        );
    }

    private static double checks(final String provider, final String outcome) {
        final Counter counter = MicrometerMetrics.getInstance().getRegistry()
            .find("pantera.auth.provider.checks")
            .tag("provider", provider).tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
