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
package com.auto1.pantera.http.rt;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.rq.RequestLine;
import org.cactoos.map.MapEntry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

/**
 * Test for {@link RtRule.ByHeader}.
 */
class RtRuleByHeaderTest {

    @Test
    void trueIfHeaderIsPresent() {
        final String name = "some header";
        Assertions.assertTrue(
            new RtRule.ByHeader(name).apply(
                new RequestLine("GET", "/"), Headers.from(new MapEntry<>(name, "any value"))
            )
        );
    }

    @Test
    void falseIfHeaderIsNotPresent() {
        Assertions.assertFalse(
            new RtRule.ByHeader("my header").apply(null, Headers.EMPTY)
        );
    }

    @Test
    void trueIfHeaderIsPresentAndValueMatchesRegex() {
        final String name = "content-type";
        Assertions.assertTrue(
            new RtRule.ByHeader(name, Pattern.compile("text/html.*")).apply(
                new RequestLine("GET", "/some/path"), Headers.from(new MapEntry<>(name, "text/html; charset=utf-8"))
            )
        );
    }

    @Test
    void falseIfHeaderIsPresentAndValueDoesNotMatchesRegex() {
        final String name = "Accept-Encoding";
        Assertions.assertFalse(
            new RtRule.ByHeader(name, Pattern.compile("gzip.*")).apply(
                new RequestLine("GET", "/another/path"), Headers.from(new MapEntry<>(name, "deflate"))
            )
        );
    }

}
