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
package com.auto1.pantera.http.headers;

import java.net.URI;
import java.net.URISyntaxException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test case for {@link ContentFileName}.
 *
 * @since 0.17.8
 */
final class ContentFileNameTest {

    @Test
    void shouldBeContentDispositionHeader() {
        MatcherAssert.assertThat(
            new ContentFileName("bar.txt").getKey(),
            new IsEqual<>("Content-Disposition")
        );
    }

    @Test
    void shouldHaveQuotedValue() {
        MatcherAssert.assertThat(
            new ContentFileName("foo.txt").getValue(),
            new IsEqual<>("attachment; filename=\"foo.txt\"")
        );
    }

    @Test
    void shouldTakeUriAsParameter() throws URISyntaxException {
        MatcherAssert.assertThat(
            new ContentFileName(
                new URI("https://example.com/index.html")
            ).getValue(),
            new IsEqual<>("attachment; filename=\"index.html\"")
        );
    }

    @Test
    void neutralisesQuotesSoNoParameterCanBeInjected() {
        MatcherAssert.assertThat(
            new ContentFileName("evil\"; filename=\"pwn.exe").getValue(),
            new IsEqual<>(
                "attachment; filename=\"evil_; filename=_pwn.exe\"; "
                    + "filename*=UTF-8''evil%22%3B%20filename%3D%22pwn.exe"
            )
        );
    }

    @Test
    void encodesNonAsciiNamesPerRfc5987() {
        MatcherAssert.assertThat(
            new ContentFileName("\u00fcnic\u00f6de-\u65e5\u672c.txt").getValue(),
            new IsEqual<>(
                "attachment; filename=\"_nic_de-__.txt\"; "
                    + "filename*=UTF-8''%C3%BCnic%C3%B6de-%E6%97%A5%E6%9C%AC.txt"
            )
        );
    }

    @Test
    void replacesBackslashesAndControlCharacters() {
        MatcherAssert.assertThat(
            new ContentFileName("a\\b\tc.txt").getValue(),
            new IsEqual<>(
                "attachment; filename=\"a_b_c.txt\"; filename*=UTF-8''a%5Cb%09c.txt"
            )
        );
    }
}
