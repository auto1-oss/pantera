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

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

/**
 * Structural filename-prefix tests for {@link ArtifactNameParser#parseMaven(String)}.
 *
 * <p>Validates the invariant: Maven filenames always start with {@code {artifactId}-},
 * which lets us detect artifact files without an extension whitelist.
 *
 * @since 2.1.2
 */
@SuppressWarnings("PMD.TooManyMethods")
final class ArtifactNameParserMavenStructuralTest {

    // ---- Standard whitelisted extensions (must still work) ----

    @ParameterizedTest
    @CsvSource({
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar,    com.google.guava.guava",
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.pom,    com.google.guava.guava",
        "/org/example/mylib/1.0.0/mylib-1.0.0.war,                   org.example.mylib",
        "/com/android/support/appcompat-v7/28.0.0/appcompat-v7-28.0.0.aar, com.android.support.appcompat-v7",
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.module, com.google.guava.guava",
    })
    void standardExtensionsStillWork(final String url, final String expected) {
        MatcherAssert.assertThat(
            "Standard ext: " + url.trim(),
            ArtifactNameParser.parse("maven-group", url.trim()),
            new IsEqual<>(Optional.of(expected.trim()))
        );
    }

    // ---- Previously non-whitelisted extensions (THE BUG FIX) ----

    @ParameterizedTest
    @CsvSource({
        // YAML — the primary bug causing 2144 ISEs
        "/com/example/config/1.0.0/config-1.0.0.yaml,         com.example.config",
        // JSON — e.g. Gradle module metadata alternatives
        "/com/example/lib/2.0.0/lib-2.0.0.json,               com.example.lib",
        // properties files
        "/org/example/app/3.1.0/app-3.1.0.properties,         org.example.app",
        // zip distribution archives
        "/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.zip, org.apache.commons.commons-lang3",
        // txt files (e.g. NOTICE, LICENSE bundled in repo)
        "/com/example/artifact/1.0/artifact-1.0.txt,          com.example.artifact",
        // html Javadoc index
        "/com/example/artifact/1.0/artifact-1.0-javadoc.html, com.example.artifact",
        // tar.gz distribution
        "/org/example/myapp/2.0.0/myapp-2.0.0.tar.gz,         org.example.myapp",
    })
    void nonWhitelistedExtensionsNowWork(final String url, final String expected) {
        MatcherAssert.assertThat(
            "Non-whitelisted ext (bug fix): " + url.trim(),
            ArtifactNameParser.parse("maven-group", url.trim()),
            new IsEqual<>(Optional.of(expected.trim()))
        );
    }

    // ---- Non-digit versions (Spring release trains, git SHAs, word versions) ----

    @ParameterizedTest
    @CsvSource({
        // Spring release train: Arabba-SR10
        "/org/springframework/data/spring-data-commons/Arabba-SR10/spring-data-commons-Arabba-SR10.jar, org.springframework.data.spring-data-commons",
        // Git SHA version (e.g. snapshot pinned to commit)
        "/com/example/lib/a82815f/lib-a82815f.jar,             com.example.lib",
        // Word version (Igor, Finchley, etc.)
        "/org/springframework/cloud/spring-cloud-dependencies/igor/spring-cloud-dependencies-igor.pom, org.springframework.cloud.spring-cloud-dependencies",
        // Purely alphabetic version
        "/org/example/app/RELEASE/app-RELEASE.jar,             org.example.app",
        // Mixed alphanumeric non-digit-leading version
        "/org/example/app/beta1/app-beta1.jar,                 org.example.app",
    })
    void nonDigitVersionsWork(final String url, final String expected) {
        MatcherAssert.assertThat(
            "Non-digit version: " + url.trim(),
            ArtifactNameParser.parse("maven-group", url.trim()),
            new IsEqual<>(Optional.of(expected.trim()))
        );
    }

    // ---- Classifiers ----

    @ParameterizedTest
    @CsvSource({
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre-javadoc.jar,  com.google.guava.guava",
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre-sources.jar,  com.google.guava.guava",
        "/com/example/mylib/1.0.0/mylib-1.0.0-tests.jar,                   com.example.mylib",
        "/org/example/app/2.0/app-2.0-native.jar,                          org.example.app",
    })
    void classifiersWork(final String url, final String expected) {
        MatcherAssert.assertThat(
            "Classifier: " + url.trim(),
            ArtifactNameParser.parse("maven-group", url.trim()),
            new IsEqual<>(Optional.of(expected.trim()))
        );
    }

    // ---- Checksums ----

    @ParameterizedTest
    @CsvSource({
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar.sha1,   com.google.guava.guava",
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.pom.md5,    com.google.guava.guava",
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar.sha256, com.google.guava.guava",
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar.sha512, com.google.guava.guava",
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.pom.asc,    com.google.guava.guava",
    })
    void checksumsWork(final String url, final String expected) {
        MatcherAssert.assertThat(
            "Checksum: " + url.trim(),
            ArtifactNameParser.parse("maven-group", url.trim()),
            new IsEqual<>(Optional.of(expected.trim()))
        );
    }

    // ---- Scala cross-version (dots in artifactId) ----

    @ParameterizedTest
    @CsvSource({
        // chill_2.12: dots in artifactId — the filename starts with "chill_2.12-"
        "/com/twitter/chill_2.12/0.10.0/chill_2.12-0.10.0.jar,       com.twitter.chill_2.12",
        "/org/typelevel/cats-core_2.13/2.9.0/cats-core_2.13-2.9.0.jar, org.typelevel.cats-core_2.13",
        "/com/typesafe/akka/akka-actor_2.13/2.8.0/akka-actor_2.13-2.8.0.jar, com.typesafe.akka.akka-actor_2.13",
    })
    void scalaCrossVersionWorks(final String url, final String expected) {
        MatcherAssert.assertThat(
            "Scala cross-version: " + url.trim(),
            ArtifactNameParser.parse("maven-group", url.trim()),
            new IsEqual<>(Optional.of(expected.trim()))
        );
    }

    // ---- Digits in artifactId ----

    @ParameterizedTest
    @CsvSource({
        // log4j-1.2-api — digit in the middle of artifactId
        "/org/apache/logging/log4j/log4j-1.2-api/2.21.0/log4j-1.2-api-2.21.0.jar, org.apache.logging.log4j.log4j-1.2-api",
        // h2 — artifactId itself is very short with digit
        "/com/h2database/h2/2.2.224/h2-2.2.224.jar, com.h2database.h2",
        // netty4 style
        "/io/netty/netty-all/4.1.100.Final/netty-all-4.1.100.Final.jar, io.netty.netty-all",
    })
    void digitsInArtifactIdWork(final String url, final String expected) {
        MatcherAssert.assertThat(
            "Digits in artifactId: " + url.trim(),
            ArtifactNameParser.parse("maven-group", url.trim()),
            new IsEqual<>(Optional.of(expected.trim()))
        );
    }

    // ---- Metadata endpoints — must return empty (triggers fanout) ----

    @ParameterizedTest
    @ValueSource(strings = {
        "/com/google/guava/guava/maven-metadata.xml",
        "/com/google/guava/guava/maven-metadata.xml.sha1",
        "/com/google/guava/guava/31.1.3-jre/maven-metadata.xml",
        "/org/apache/maven/plugins/maven-compiler-plugin/maven-metadata.xml",
        "/org/springframework/spring-core/maven-metadata.xml",
    })
    void mavenMetadataReturnsEmpty(final String url) {
        MatcherAssert.assertThat(
            "Metadata should trigger fanout (empty): " + url,
            ArtifactNameParser.parse("maven-group", url),
            new IsEqual<>(Optional.empty())
        );
    }

    // ---- Short paths — fewer than 4 segments → empty ----

    @ParameterizedTest
    @ValueSource(strings = {
        "/",
        "",
        "/com",
        "/com/google",
        "/com/google/guava",
    })
    void shortPathsReturnEmpty(final String url) {
        MatcherAssert.assertThat(
            "Short path should return empty: '" + url + "'",
            ArtifactNameParser.parse("maven-group", url),
            new IsEqual<>(Optional.empty())
        );
    }

    // ---- SNAPSHOT versions with timestamps ----

    @Test
    void snapshotTimestampedArtifact() {
        MatcherAssert.assertThat(
            "SNAPSHOT with timestamp",
            ArtifactNameParser.parse(
                "maven-group",
                "/org/example/mylib/1.0-SNAPSHOT/mylib-1.0-20230101.120000-1.jar"
            ),
            new IsEqual<>(Optional.of("org.example.mylib"))
        );
    }

    // ---- Single-segment groupId ----

    @Test
    void singleSegmentGroupId() {
        MatcherAssert.assertThat(
            "Single-segment groupId: junit",
            ArtifactNameParser.parse(
                "maven-group",
                "/junit/junit/4.13.2/junit-4.13.2.jar"
            ),
            new IsEqual<>(Optional.of("junit.junit"))
        );
    }

    // ---- Deep groupId path ----

    @Test
    void deepGroupIdPath() {
        MatcherAssert.assertThat(
            "Deep groupId path",
            ArtifactNameParser.parse(
                "maven-group",
                "/org/apache/maven/plugins/maven-compiler-plugin/3.11.0/maven-compiler-plugin-3.11.0.pom"
            ),
            new IsEqual<>(Optional.of("org.apache.maven.plugins.maven-compiler-plugin"))
        );
    }

    // ---- Without leading slash ----

    @Test
    void withoutLeadingSlash() {
        MatcherAssert.assertThat(
            "Without leading slash",
            ArtifactNameParser.parse(
                "maven-group",
                "com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar"
            ),
            new IsEqual<>(Optional.of("com.google.guava.guava"))
        );
    }

    // ---- Gradle uses same parser ----

    @Test
    void gradleUsesStructuralParser() {
        MatcherAssert.assertThat(
            "Gradle also uses structural parser",
            ArtifactNameParser.parse(
                "gradle-group",
                "/org/gradle/gradle-tooling-api/8.5/gradle-tooling-api-8.5.jar"
            ),
            new IsEqual<>(Optional.of("org.gradle.gradle-tooling-api"))
        );
    }
}
