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
package com.auto1.pantera.http.security;

import java.util.Optional;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests for {@link PathTraversalGuard}. Covers the 20+ malicious vector list
 * required by T-S01 plus the legitimate-path passes.
 *
 * @since 2.2.0
 */
final class PathTraversalGuardTest {

    /**
     * Malicious paths that must all be rejected by the guard. Mixed list
     * sourced from OWASP path-traversal cheat-sheet, Artifactory CVE
     * disclosures, and observed-in-wild log scans. 25 vectors — meets the
     * "20+" acceptance criterion.
     */
    @ParameterizedTest(name = "rejects unsafe path: {0}")
    @ValueSource(strings = {
        // Plain ../ traversal
        "/../etc/passwd",
        "/../../etc/passwd",
        "/../../../etc/passwd",
        "/a/../etc/passwd",
        "/a/b/../../etc/passwd",
        "/foo/../../../sensitive.txt",
        // Encoded ../ traversal
        "/a/%2e%2e/b",
        "/a/%2E%2E/b",
        "/%2e%2e/etc/passwd",
        "/%2E%2E/%2E%2E/etc/passwd",
        // Mixed encoded + literal ../ traversal
        "/%2e./b",
        "/.%2e/b",
        // Double-encoded ../ traversal
        "/a/%252e%252e/b",
        "/%252e%252e/etc/passwd",
        // NUL byte attacks
        "/a%00b",
        "/foo%00.jar",
        // Backslash (Windows-style) traversal
        "/a\\..\\b",
        "/foo\\bar",
        // CR/LF / header-splitting probe
        "/foo\r\nX-Injected: 1",
        "/foo\nX-Injected: 1",
        // Other low-level control characters
        "/foobar",
        "/foobar",
        // Mixed-case encoded dot
        "/foo/%2E%2e/bar",
        // Encoded slash with traversal segment
        "/foo/..%2F../etc/passwd",
        // Double-encoded NUL
        "/foo%2500.jar"
    })
    void rejectsUnsafePaths(final String input) {
        final Optional<String> result =
            PathTraversalGuard.instance().canonicalise(input);
        assertThat(result.isPresent(), new IsEqual<>(false));
    }

    @ParameterizedTest(name = "accepts legitimate path: {0}")
    @ValueSource(strings = {
        // Maven coordinates
        "/com/example/foo/1.0/foo-1.0.jar",
        "/com/example/foo/1.0/foo-1.0.pom",
        "/com/example/foo/1.0/foo-1.0.jar.sha1",
        "/org/apache/maven/plugins/maven-compiler-plugin/3.11.0/maven-compiler-plugin-3.11.0.jar",
        // npm packages
        "/lodash/-/lodash-4.17.21.tgz",
        "/@scope/package/-/package-1.0.0.tgz",
        // PyPI wheels
        "/packages/numpy/numpy-2.0.0-cp311-cp311-manylinux_2_17_x86_64.whl",
        // Go modules
        "/github.com/foo/bar/@v/v1.0.0.zip",
        "/github.com/foo/bar/@v/v1.0.0.info",
        // Docker registry blob (sha256 digest)
        "/v2/library/alpine/blobs/sha256:abc123",
        // Helm chart
        "/charts/nginx-1.0.0.tgz",
        // Composer
        "/p2/foo/bar.json",
        // Hexpm tarball
        "/tarballs/jason-1.4.0.tar",
        // Encoded space (legitimate)
        "/com/example/with%20space/1.0/file.jar",
        // Maven repos with dots in version
        "/com/example/foo/1.2.3-SNAPSHOT/foo-1.2.3-SNAPSHOT.jar",
        // Files with .. in name but as a non-segment (decoded form has ..
        // only inside a longer token, never as a complete segment)
        "/files/v1..2/release.bin"
    })
    void acceptsLegitimatePaths(final String input) {
        final Optional<String> result =
            PathTraversalGuard.instance().canonicalise(input);
        assertThat(result.isPresent(), new IsEqual<>(true));
    }

    @Test
    @DisplayName("null input rejected")
    void nullInputRejected() {
        assertThat(
            PathTraversalGuard.instance().canonicalise(null).isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    @DisplayName("empty input rejected")
    void emptyInputRejected() {
        assertThat(
            PathTraversalGuard.instance().canonicalise("").isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    @DisplayName("malformed percent encoding rejected")
    void malformedPercentEncodingRejected() {
        assertThat(
            "reason: lone percent — no two hex digits follow",
            PathTraversalGuard.instance().canonicalise("/a%ZZb").isPresent(),
            new IsEqual<>(false)
        );
        assertThat(
            "reason: trailing percent — no two hex digits follow",
            PathTraversalGuard.instance().canonicalise("/a%").isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    @DisplayName("decoded form is returned for safe paths")
    void decodedFormReturnedForSafePaths() {
        assertThat(
            PathTraversalGuard.instance()
                .canonicalise("/com/example/with%20space/file.jar")
                .orElse(""),
            new IsEqual<>("/com/example/with space/file.jar")
        );
    }

    @Test
    @DisplayName("canonical form preserves valid path verbatim when no decoding needed")
    void canonicalFormPreservesPlainPath() {
        final String plain = "/com/example/foo/1.0/foo-1.0.jar";
        assertThat(
            PathTraversalGuard.instance().canonicalise(plain).orElse(""),
            new IsEqual<>(plain)
        );
    }
}
