/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cooldown.metadata;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

/**
 * Tests for {@link AllVersionsBlockedException}.
 *
 * @since 1.0
 */
final class AllVersionsBlockedExceptionTest {

    @Test
    void containsPackageName() {
        final AllVersionsBlockedException exception = new AllVersionsBlockedException(
            "lodash",
            Set.of("4.17.21", "4.17.20")
        );
        assertThat(exception.packageName(), equalTo("lodash"));
    }

    @Test
    void containsBlockedVersions() {
        final Set<String> versions = Set.of("1.0.0", "2.0.0", "3.0.0");
        final AllVersionsBlockedException exception = new AllVersionsBlockedException(
            "test-pkg",
            versions
        );
        assertThat(exception.blockedVersions(), hasItems("1.0.0", "2.0.0", "3.0.0"));
        assertThat(exception.blockedVersions().size(), equalTo(3));
    }

    @Test
    void messageContainsDetails() {
        final AllVersionsBlockedException exception = new AllVersionsBlockedException(
            "express",
            Set.of("4.18.0", "4.17.0")
        );
        assertThat(exception.getMessage(), containsString("express"));
        assertThat(exception.getMessage(), containsString("2 versions"));
    }

    @Test
    void blockedVersionsAreUnmodifiable() {
        final Set<String> versions = Set.of("1.0.0");
        final AllVersionsBlockedException exception = new AllVersionsBlockedException(
            "pkg",
            versions
        );
        
        org.junit.jupiter.api.Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> exception.blockedVersions().add("2.0.0")
        );
    }
}
