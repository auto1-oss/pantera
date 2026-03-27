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
package com.auto1.pantera.group;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link RoutingRule}.
 */
class RoutingRuleTest {

    @Test
    void pathPrefixMatchesExactPrefix() {
        final RoutingRule rule = new RoutingRule.PathPrefix("repo1", "com/mycompany/");
        assertThat(rule.matches("/com/mycompany/foo/1.0/foo-1.0.jar"), is(true));
        assertThat(rule.matches("com/mycompany/bar"), is(true));
    }

    @Test
    void pathPrefixDoesNotMatchDifferentPrefix() {
        final RoutingRule rule = new RoutingRule.PathPrefix("repo1", "com/mycompany/");
        assertThat(rule.matches("/org/apache/foo"), is(false));
        assertThat(rule.matches("org/other/bar"), is(false));
    }

    @Test
    void pathPrefixNormalizesLeadingSlash() {
        final RoutingRule rule = new RoutingRule.PathPrefix("repo1", "com/example/");
        assertThat(rule.matches("/com/example/test"), is(true));
        assertThat(rule.matches("com/example/test"), is(true));
    }

    @Test
    void pathPatternMatchesRegex() {
        final RoutingRule rule = new RoutingRule.PathPattern("repo1", "org/apache/.*");
        assertThat(rule.matches("/org/apache/commons/1.0/commons-1.0.jar"), is(true));
        assertThat(rule.matches("org/apache/maven/settings.xml"), is(true));
    }

    @Test
    void pathPatternDoesNotMatchDifferentPath() {
        final RoutingRule rule = new RoutingRule.PathPattern("repo1", "org/apache/.*");
        assertThat(rule.matches("/com/example/foo"), is(false));
    }

    @Test
    void pathPatternNormalizesLeadingSlash() {
        final RoutingRule rule = new RoutingRule.PathPattern("repo1", "com/.*\\.jar");
        assertThat(rule.matches("/com/example/foo-1.0.jar"), is(true));
        assertThat(rule.matches("com/example/foo-1.0.jar"), is(true));
        assertThat(rule.matches("/com/example/foo-1.0.pom"), is(false));
    }

    @Test
    void memberReturnsMemberName() {
        assertThat(
            new RoutingRule.PathPrefix("test-member", "com/").member(),
            equalTo("test-member")
        );
        assertThat(
            new RoutingRule.PathPattern("test-member", ".*").member(),
            equalTo("test-member")
        );
    }

    @Test
    void pathPrefixRejectsNullMember() {
        assertThrows(
            NullPointerException.class,
            () -> new RoutingRule.PathPrefix(null, "com/")
        );
    }

    @Test
    void pathPrefixRejectsNullPrefix() {
        assertThrows(
            NullPointerException.class,
            () -> new RoutingRule.PathPrefix("member", null)
        );
    }

    @Test
    void pathPatternRejectsInvalidRegex() {
        assertThrows(
            java.util.regex.PatternSyntaxException.class,
            () -> new RoutingRule.PathPattern("member", "[invalid")
        );
    }
}
