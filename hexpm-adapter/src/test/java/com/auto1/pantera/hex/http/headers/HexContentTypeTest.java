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
package com.auto1.pantera.hex.http.headers;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.ContentType;
import java.util.Map;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Test for {@link HexContentType}.
 */
class HexContentTypeTest {

    @Test
    void shouldFillDefaultValue() {
        final String accept = HexContentType.DEFAULT_TYPE;
        final Headers headers = new HexContentType(Headers.from()).fill();
        String result = "";
        for (final Map.Entry<String, String> header : headers) {
            if (ContentType.NAME.equals(header.getKey())) {
                result = header.getValue();
            }
        }
        MatcherAssert.assertThat(
            result,
            new IsEqual<>(accept)
        );
    }

    @Test
    void shouldFillFromAcceptHeaderWhenNameInLowerCase() {
        final String accept = "application/vnd.hex+json";
        final Headers rqheader = Headers.from("accept", accept);
        final Headers headers = new HexContentType(rqheader).fill();
        String result = "";
        for (final Map.Entry<String, String> header : headers) {
            if (ContentType.NAME.equals(header.getKey())) {
                result = header.getValue();
            }
        }
        MatcherAssert.assertThat(
            result,
            new IsEqual<>(accept)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "application/vnd.hex+erlang",
        "application/vnd.hex+json",
        "application/json"
    })
    void shouldFillFromAcceptHeader(final String accept) {
        final Headers rqheader = Headers.from("Accept", accept);
        final Headers headers = new HexContentType(rqheader).fill();
        String result = "";
        for (final Map.Entry<String, String> header : headers) {
            if (ContentType.NAME.equals(header.getKey())) {
                result = header.getValue();
            }
        }
        MatcherAssert.assertThat(
            result,
            new IsEqual<>(accept)
        );
    }
}
