/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
