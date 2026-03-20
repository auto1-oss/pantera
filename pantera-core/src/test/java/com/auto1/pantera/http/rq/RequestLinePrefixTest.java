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
package com.auto1.pantera.http.rq;

import com.auto1.pantera.http.Headers;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Test for {@link RequestLinePrefix}.
 * @since 0.16
 */
class RequestLinePrefixTest {

    @ParameterizedTest
    @CsvSource({
        "/one/two/three,/three,/one/two",
        "/one/two/three,/two/three,/one",
        "/one/two/three,'',/one/two/three",
        "/one/two/three,/,/one/two/three",
        "/one/two,/two/,/one",
        "'',/test,''",
        "'','',''"
    })
    void returnsPrefix(final String full, final String line, final String res) {
        MatcherAssert.assertThat(
            new RequestLinePrefix(line, Headers.from("X-FullPath", full)).get(),
            new IsEqual<>(res)
        );
    }
}
