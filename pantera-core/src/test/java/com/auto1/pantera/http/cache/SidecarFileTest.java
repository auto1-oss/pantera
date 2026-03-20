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

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SidecarFile}.
 */
class SidecarFileTest {

    @Test
    void createsWithPathAndContent() {
        final String path = "com/example/foo/1.0/foo-1.0.jar.sha256";
        final byte[] content = "abc123".getBytes(StandardCharsets.UTF_8);
        final SidecarFile sidecar = new SidecarFile(path, content);
        assertThat(sidecar.path(), equalTo(path));
        assertThat(sidecar.content(), equalTo(content));
    }

    @Test
    void rejectsNullPath() {
        assertThrows(
            NullPointerException.class,
            () -> new SidecarFile(null, new byte[]{1})
        );
    }

    @Test
    void rejectsNullContent() {
        assertThrows(
            NullPointerException.class,
            () -> new SidecarFile("path", null)
        );
    }
}
