/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.backfill;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ScannerFactory}.
 *
 * @since 1.20.13
 */
final class ScannerFactoryTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "maven", "gradle", "docker", "npm", "pypi",
        "go", "helm", "composer", "php", "file",
        "deb", "debian", "gem", "gems",
        "maven-proxy", "gradle-proxy", "docker-proxy",
        "npm-proxy", "pypi-proxy", "go-proxy",
        "helm-proxy", "php-proxy", "file-proxy",
        "deb-proxy", "debian-proxy", "gem-proxy"
    })
    void createsNonNullScannerForKnownTypes(final String type) {
        MatcherAssert.assertThat(
            String.format("Scanner for type '%s' must not be null", type),
            ScannerFactory.create(type),
            Matchers.notNullValue()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "MAVEN", "Docker", "NPM", "PyPi", "HELM"
    })
    void handlesUpperCaseTypes(final String type) {
        MatcherAssert.assertThat(
            String.format("Scanner for type '%s' (case-insensitive) must not be null", type),
            ScannerFactory.create(type),
            Matchers.notNullValue()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "svn", ""})
    void throwsForUnknownType(final String type) {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> ScannerFactory.create(type),
            String.format("Expected IllegalArgumentException for unknown type '%s'", type)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"maven", "gradle"})
    void mavenAndGradleReturnMavenScanner(final String type) {
        MatcherAssert.assertThat(
            String.format("Type '%s' should produce a MavenScanner", type),
            ScannerFactory.create(type),
            Matchers.instanceOf(MavenScanner.class)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"composer", "php"})
    void composerAndPhpReturnComposerScanner(final String type) {
        MatcherAssert.assertThat(
            String.format("Type '%s' should produce a ComposerScanner", type),
            ScannerFactory.create(type),
            Matchers.instanceOf(ComposerScanner.class)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"deb", "debian"})
    void debAndDebianReturnDebianScanner(final String type) {
        MatcherAssert.assertThat(
            String.format("Type '%s' should produce a DebianScanner", type),
            ScannerFactory.create(type),
            Matchers.instanceOf(DebianScanner.class)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"gem", "gems"})
    void gemAndGemsReturnGemScanner(final String type) {
        MatcherAssert.assertThat(
            String.format("Type '%s' should produce a GemScanner", type),
            ScannerFactory.create(type),
            Matchers.instanceOf(GemScanner.class)
        );
    }
}
