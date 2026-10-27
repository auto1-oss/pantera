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
package com.auto1.pantera.cooldown.config;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CooldownExemptions} and the exemption lookup of
 * {@link CooldownSettings}.
 *
 * @since 2.2.11
 */
final class CooldownExemptionsTest {

    @Test
    void matchesExactNamesCaseInsensitively() {
        final CooldownExemptions ex = new CooldownExemptions(
            List.of(CooldownExemptionsTest.rule("@Auto1/Design-System", Optional.empty()))
        );
        MatcherAssert.assertThat("same case", ex.exempts("@auto1/design-system"), new IsEqual<>(true));
        MatcherAssert.assertThat("other case", ex.exempts("@AUTO1/DESIGN-SYSTEM"), new IsEqual<>(true));
        MatcherAssert.assertThat("longer name", ex.exempts("@auto1/design-system-x"), new IsEqual<>(false));
    }

    @Test
    void matchesPrefixRules() {
        final CooldownExemptions ex = new CooldownExemptions(
            List.of(
                CooldownExemptionsTest.rule("com.auto1.*", Optional.empty()),
                CooldownExemptionsTest.rule("library/nginx*", Optional.empty())
            )
        );
        MatcherAssert.assertThat("maven group", ex.exempts("com.auto1.platform.core"), new IsEqual<>(true));
        MatcherAssert.assertThat("docker image", ex.exempts("library/nginx"), new IsEqual<>(true));
        MatcherAssert.assertThat("docker sibling", ex.exempts("library/nginx-unprivileged"), new IsEqual<>(true));
        MatcherAssert.assertThat("other group", ex.exempts("com.auto2.platform"), new IsEqual<>(false));
        MatcherAssert.assertThat("no name", ex.exempts(null), new IsEqual<>(false));
    }

    @Test
    void expiredRulesStopApplying() {
        final CooldownExemptions ex = new CooldownExemptions(
            List.of(
                CooldownExemptionsTest.rule("old-pkg", Optional.of(Instant.now().minusSeconds(1))),
                CooldownExemptionsTest.rule("new-*", Optional.of(Instant.now().plus(Duration.ofDays(1))))
            )
        );
        MatcherAssert.assertThat("expired exact", ex.exempts("old-pkg"), new IsEqual<>(false));
        MatcherAssert.assertThat("unexpired prefix", ex.exempts("new-pkg"), new IsEqual<>(true));
    }

    @Test
    void longestLivedMatchingRuleWins() {
        final CooldownExemptions ex = new CooldownExemptions(
            List.of(
                CooldownExemptionsTest.rule("pkg", Optional.of(Instant.now().minusSeconds(1))),
                CooldownExemptionsTest.rule("pk*", Optional.empty())
            )
        );
        MatcherAssert.assertThat(ex.exempts("pkg"), new IsEqual<>(true));
    }

    @Test
    void rejectsInvalidPatterns() {
        for (final String bad : List.of("", "*", "a*b", "has space", "two**")) {
            Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> CooldownExemptionsTest.rule(bad, Optional.empty()),
                "pattern '" + bad + "'"
            );
        }
    }

    @Test
    void settingsLookUpExemptionsPerRepository() {
        final CooldownSettings settings = new CooldownSettings(true, Duration.ofDays(7));
        settings.setRepoExemptions(
            "npm-proxy",
            new CooldownExemptions(List.of(CooldownExemptionsTest.rule("@auto1/*", Optional.empty())))
        );
        MatcherAssert.assertThat("exempt in its repo", settings.exempt("npm-proxy", "@auto1/ui"), new IsEqual<>(true));
        MatcherAssert.assertThat("not in another repo", settings.exempt("npm-other", "@auto1/ui"), new IsEqual<>(false));
        MatcherAssert.assertThat("null repo", settings.exempt(null, "@auto1/ui"), new IsEqual<>(false));
        settings.setRepoExemptions("npm-proxy", CooldownExemptions.NONE);
        MatcherAssert.assertThat("removed", settings.exempt("npm-proxy", "@auto1/ui"), new IsEqual<>(false));
    }

    private static CooldownExemptions.Rule rule(final String pattern, final Optional<Instant> expires) {
        return new CooldownExemptions.Rule(pattern, expires, "", "tester", Instant.EPOCH);
    }
}
