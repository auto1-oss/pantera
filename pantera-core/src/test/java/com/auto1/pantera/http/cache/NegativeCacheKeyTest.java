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
package com.auto1.pantera.http.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link NegativeCacheKey}.
 *
 * @since 2.2.0
 */
final class NegativeCacheKeyTest {

    @Test
    void flatProducesExpectedString() {
        final NegativeCacheKey key = new NegativeCacheKey(
            "libs-release", "maven", "org.spring:spring-core", "5.3.0"
        );
        assertEquals("libs-release:maven:org.spring:spring-core:5.3.0", key.flat());
    }

    @Test
    void flatWithEmptyVersion() {
        final NegativeCacheKey key = new NegativeCacheKey(
            "npm-proxy", "npm", "@scope/pkg", ""
        );
        assertEquals("npm-proxy:npm:@scope/pkg:", key.flat());
    }

    @Test
    void flatWithNullVersionDefaultsToEmpty() {
        final NegativeCacheKey key = new NegativeCacheKey(
            "pypi-group", "pypi", "requests", null
        );
        assertEquals("pypi-group:pypi:requests:", key.flat());
        assertEquals("", key.artifactVersion());
    }

    @Test
    void nullScopeThrows() {
        assertThrows(NullPointerException.class, () ->
            new NegativeCacheKey(null, "maven", "foo", "1.0")
        );
    }

    @Test
    void nullRepoTypeThrows() {
        assertThrows(NullPointerException.class, () ->
            new NegativeCacheKey("scope", null, "foo", "1.0")
        );
    }

    @Test
    void nullArtifactNameThrows() {
        assertThrows(NullPointerException.class, () ->
            new NegativeCacheKey("scope", "maven", null, "1.0")
        );
    }

    @Test
    void recordAccessorsWork() {
        final NegativeCacheKey key = new NegativeCacheKey(
            "docker-proxy", "docker", "nginx", "latest"
        );
        assertEquals("docker-proxy", key.scope());
        assertEquals("docker", key.repoType());
        assertEquals("nginx", key.artifactName());
        assertEquals("latest", key.artifactVersion());
    }

    @Test
    void equalityByValue() {
        final NegativeCacheKey a = new NegativeCacheKey("s", "t", "n", "v");
        final NegativeCacheKey b = new NegativeCacheKey("s", "t", "n", "v");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void toStringIsNotNull() {
        final NegativeCacheKey key = new NegativeCacheKey("s", "t", "n", "v");
        assertNotNull(key.toString());
    }
}
