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
package com.auto1.pantera.http.observability;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link UserAgentParser}. Covers every client family and OS family
 * the legacy {@code EcsLogEvent.parseUserAgent} recognised, plus null / unknown
 * edge cases. The parser is behaviour-preserving from EcsLogEvent v1.18.23 —
 * these tests pin that behaviour so the observability-tier re-lift (WI-post-03b)
 * cannot regress the Kibana sub-field shape.
 */
final class UserAgentParserTest {

    @Test
    @DisplayName("Maven UA → name=Maven, version extracted")
    void parsesMavenClient() {
        final UserAgentParser.UserAgentInfo info =
            UserAgentParser.parse("Maven/3.9.6 (Java 21.0.3; Linux 6.12.68)");
        MatcherAssert.assertThat(info.name(), Matchers.is("Maven"));
        MatcherAssert.assertThat(info.version(), Matchers.is("3.9.6"));
    }

    @Test
    @DisplayName("npm UA → name=npm, version extracted")
    void parsesNpmClient() {
        final UserAgentParser.UserAgentInfo info =
            UserAgentParser.parse("npm/10.2.4 node/v20.11.0 darwin x64");
        MatcherAssert.assertThat(info.name(), Matchers.is("npm"));
        MatcherAssert.assertThat(info.version(), Matchers.is("10.2.4"));
    }

    @Test
    @DisplayName("pip UA → name=pip, version extracted")
    void parsesPipClient() {
        final UserAgentParser.UserAgentInfo info =
            UserAgentParser.parse("pip/23.3.1 {\"python\":\"3.11.5\"}");
        MatcherAssert.assertThat(info.name(), Matchers.is("pip"));
        MatcherAssert.assertThat(info.version(), Matchers.is("23.3.1"));
    }

    @Test
    @DisplayName("Docker-Client UA → name=Docker, version extracted")
    void parsesDockerClient() {
        final UserAgentParser.UserAgentInfo info =
            UserAgentParser.parse("Docker-Client/24.0.7 (linux)");
        MatcherAssert.assertThat(info.name(), Matchers.is("Docker"));
        MatcherAssert.assertThat(info.version(), Matchers.is("24.0.7"));
    }

    @Test
    @DisplayName("Go-http-client UA → name=Go, version extracted")
    void parsesGoClient() {
        final UserAgentParser.UserAgentInfo info =
            UserAgentParser.parse("Go-http-client/1.1");
        MatcherAssert.assertThat(info.name(), Matchers.is("Go"));
        MatcherAssert.assertThat(info.version(), Matchers.is("1.1"));
    }

    @Test
    @DisplayName("Gradle UA → name=Gradle, version extracted")
    void parsesGradleClient() {
        final UserAgentParser.UserAgentInfo info =
            UserAgentParser.parse("Gradle/8.5 (Linux 6.1; amd64; OpenJDK 21)");
        MatcherAssert.assertThat(info.name(), Matchers.is("Gradle"));
        MatcherAssert.assertThat(info.version(), Matchers.is("8.5"));
    }

    @Test
    @DisplayName("Composer UA → name=Composer, version extracted")
    void parsesComposerClient() {
        final UserAgentParser.UserAgentInfo info =
            UserAgentParser.parse("Composer/2.7.1 (Linux; PHP 8.2.15)");
        MatcherAssert.assertThat(info.name(), Matchers.is("Composer"));
        MatcherAssert.assertThat(info.version(), Matchers.is("2.7.1"));
    }

    @Test
    @DisplayName("curl UA → name=curl, version extracted")
    void parsesCurl() {
        final UserAgentParser.UserAgentInfo info =
            UserAgentParser.parse("curl/8.4.0");
        MatcherAssert.assertThat(info.name(), Matchers.is("curl"));
        MatcherAssert.assertThat(info.version(), Matchers.is("8.4.0"));
    }

    @Test
    @DisplayName("wget UA → name=wget, version extracted")
    void parsesWget() {
        final UserAgentParser.UserAgentInfo info =
            UserAgentParser.parse("Wget wget/1.21.4");
        MatcherAssert.assertThat(info.name(), Matchers.is("wget"));
        MatcherAssert.assertThat(info.version(), Matchers.is("1.21.4"));
    }

    @Test
    @DisplayName("Linux UA token → osName=Linux")
    void parsesLinuxOs() {
        final UserAgentParser.UserAgentInfo info =
            UserAgentParser.parse("Maven/3.9.6 (Java 21.0.3; Linux 6.12.68)");
        MatcherAssert.assertThat(info.osName(), Matchers.is("Linux"));
    }

    @Test
    @DisplayName("Windows UA token → osName=Windows")
    void parsesWindowsOs() {
        final UserAgentParser.UserAgentInfo info =
            UserAgentParser.parse("Maven/3.9.6 (Java 17; Windows 10 10.0)");
        MatcherAssert.assertThat(info.osName(), Matchers.is("Windows"));
    }

    @Test
    @DisplayName("Mac OS X UA token → osName=macOS")
    void parsesMacOs() {
        final UserAgentParser.UserAgentInfo info =
            UserAgentParser.parse("Maven/3.9.6 (Java 21; Mac OS X 14.2)");
        MatcherAssert.assertThat(info.osName(), Matchers.is("macOS"));
    }

    @Test
    @DisplayName("FreeBSD UA token → osName=FreeBSD")
    void parsesFreeBsdOs() {
        final UserAgentParser.UserAgentInfo info =
            UserAgentParser.parse("Maven/3.9.6 (Java 17; FreeBSD 13.2)");
        MatcherAssert.assertThat(info.osName(), Matchers.is("FreeBSD"));
    }

    @Test
    @DisplayName("Java version token → osVersion (preserve EcsLogEvent behaviour)")
    void javaVersionGoesIntoOsVersion() {
        final UserAgentParser.UserAgentInfo info =
            UserAgentParser.parse("Maven/3.9.6 (Java/21.0.3 Linux 6.12)");
        MatcherAssert.assertThat(info.osVersion(), Matchers.is("21.0.3"));
    }

    @Test
    @DisplayName("null UA → all fields null, never throws")
    void parseReturnsEmptyForNull() {
        final UserAgentParser.UserAgentInfo info = UserAgentParser.parse(null);
        MatcherAssert.assertThat(info.name(), Matchers.nullValue());
        MatcherAssert.assertThat(info.version(), Matchers.nullValue());
        MatcherAssert.assertThat(info.osName(), Matchers.nullValue());
        MatcherAssert.assertThat(info.osVersion(), Matchers.nullValue());
        MatcherAssert.assertThat(info.deviceName(), Matchers.nullValue());
    }

    @Test
    @DisplayName("unknown UA → name/version null, but OS may still resolve")
    void parseReturnsEmptyForUnknownUa() {
        final UserAgentParser.UserAgentInfo info =
            UserAgentParser.parse("TotallyMadeUpClient/9.9 (AmigaOS)");
        MatcherAssert.assertThat(info.name(), Matchers.nullValue());
        MatcherAssert.assertThat(info.version(), Matchers.nullValue());
        MatcherAssert.assertThat(info.osName(), Matchers.nullValue());
    }

    @Test
    @DisplayName("empty UA → all fields null")
    void parseReturnsEmptyForEmptyString() {
        final UserAgentParser.UserAgentInfo info = UserAgentParser.parse("");
        MatcherAssert.assertThat(info.name(), Matchers.nullValue());
        MatcherAssert.assertThat(info.version(), Matchers.nullValue());
        MatcherAssert.assertThat(info.osName(), Matchers.nullValue());
        MatcherAssert.assertThat(info.osVersion(), Matchers.nullValue());
        MatcherAssert.assertThat(info.deviceName(), Matchers.nullValue());
    }
}
