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
package com.auto1.pantera.docker.misc;

import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

/**
 * Test for {@link RqByRegex}.
 */
class RqByRegexTest {

    @Test
    void shouldMatchPath() {
        Assertions.assertTrue(
            new RqByRegex(new RequestLine(RqMethod.GET, "/v2/some/repo"),
                Pattern.compile("/v2/.*")).path().matches()
        );
    }

    @Test
    void shouldThrowExceptionIsDoesNotMatch() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new RqByRegex(new RequestLine(RqMethod.GET, "/v3/my-repo/blobs"),
                Pattern.compile("/v2/.*/blobs")).path()
        );
    }

}
