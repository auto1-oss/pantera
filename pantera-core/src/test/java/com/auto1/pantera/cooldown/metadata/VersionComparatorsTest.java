/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cooldown.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;

/**
 * Tests for {@link VersionComparators}.
 *
 * @since 1.0
 */
final class VersionComparatorsTest {

    @ParameterizedTest
    @CsvSource({
        "1.0.0, 2.0.0, -1",
        "2.0.0, 1.0.0, 1",
        "1.0.0, 1.0.0, 0",
        "1.0.0, 1.1.0, -1",
        "1.1.0, 1.0.0, 1",
        "1.0.0, 1.0.1, -1",
        "1.0.1, 1.0.0, 1",
        "1.0.0-alpha, 1.0.0, -1",
        "1.0.0, 1.0.0-alpha, 1",
        "1.0.0-alpha, 1.0.0-beta, -1",
        "v1.0.0, v2.0.0, -1",
        "1.0, 1.0.0, 0",
        "1, 1.0.0, 0"
    })
    void semverComparesCorrectly(final String v1, final String v2, final int expected) {
        final Comparator<String> comparator = VersionComparators.semver();
        final int result = comparator.compare(v1, v2);
        if (expected < 0) {
            assertThat(result, lessThan(0));
        } else if (expected > 0) {
            assertThat(result, greaterThan(0));
        } else {
            assertThat(result, equalTo(0));
        }
    }

    @Test
    void semverSortsVersionsCorrectly() {
        final List<String> versions = Arrays.asList(
            "1.0.0", "2.0.0", "1.1.0", "1.0.1", "1.0.0-alpha", "1.0.0-beta", "0.9.0"
        );
        final List<String> sorted = versions.stream()
            .sorted(VersionComparators.semver())
            .collect(Collectors.toList());
        assertThat(sorted, equalTo(Arrays.asList(
            "0.9.0", "1.0.0-alpha", "1.0.0-beta", "1.0.0", "1.0.1", "1.1.0", "2.0.0"
        )));
    }

    @Test
    void semverSortsDescendingForLatest() {
        final List<String> versions = Arrays.asList(
            "1.0.0", "2.0.0", "1.1.0", "1.0.1"
        );
        final List<String> sorted = versions.stream()
            .sorted(VersionComparators.semver().reversed())
            .collect(Collectors.toList());
        assertThat(sorted.get(0), equalTo("2.0.0"));
    }

    @ParameterizedTest
    @CsvSource({
        "1.0, 2.0, -1",
        "1.0.0, 1.0.1, -1",
        "1.0-SNAPSHOT, 1.0, -1",
        "1.0-alpha, 1.0-beta, -1",
        "1.0-beta, 1.0-rc, -1",
        "1.0-rc, 1.0, -1",
        "1.0, 1.0-sp, -1"
    })
    void mavenComparesCorrectly(final String v1, final String v2, final int expected) {
        final Comparator<String> comparator = VersionComparators.maven();
        final int result = comparator.compare(v1, v2);
        if (expected < 0) {
            assertThat(result, lessThan(0));
        } else if (expected > 0) {
            assertThat(result, greaterThan(0));
        } else {
            assertThat(result, equalTo(0));
        }
    }

    @Test
    void mavenSortsVersionsCorrectly() {
        final List<String> versions = Arrays.asList(
            "1.0", "1.0-SNAPSHOT", "1.0-alpha", "1.0-beta", "1.0-rc", "1.1"
        );
        final List<String> sorted = versions.stream()
            .sorted(VersionComparators.maven())
            .collect(Collectors.toList());
        assertThat(sorted, equalTo(Arrays.asList(
            "1.0-alpha", "1.0-beta", "1.0-rc", "1.0-SNAPSHOT", "1.0", "1.1"
        )));
    }

    @Test
    void lexicalComparesStrings() {
        final Comparator<String> comparator = VersionComparators.lexical();
        assertThat(comparator.compare("v1.0.0", "v2.0.0"), lessThan(0));
        assertThat(comparator.compare("v2.0.0", "v1.0.0"), greaterThan(0));
        assertThat(comparator.compare("v1.0.0", "v1.0.0"), equalTo(0));
    }
}
