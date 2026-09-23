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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link SimpleApiFormat}.
 */
class SimpleApiFormatTest {

    @Test
    void defaultsToHtml() {
        MatcherAssert.assertThat(
            SimpleApiFormat.fromHeaders(Headers.EMPTY),
            new IsEqual<>(SimpleApiFormat.HTML)
        );
    }

    @Test
    void detectsJsonAcceptHeader() {
        MatcherAssert.assertThat(
            SimpleApiFormat.fromHeaders(
                Headers.from(new Header("Accept", "application/vnd.pypi.simple.v1+json"))
            ),
            new IsEqual<>(SimpleApiFormat.JSON)
        );
    }

    @Test
    void detectsJsonWithQualityFactor() {
        MatcherAssert.assertThat(
            SimpleApiFormat.fromHeaders(
                Headers.from(
                    new Header(
                        "Accept",
                        "application/vnd.pypi.simple.v1+json, text/html;q=0.9"
                    )
                )
            ),
            new IsEqual<>(SimpleApiFormat.JSON)
        );
    }

    @Test
    void detectsExplicitHtml() {
        MatcherAssert.assertThat(
            SimpleApiFormat.fromHeaders(
                Headers.from(
                    new Header("Accept", "application/vnd.pypi.simple.v1+html")
                )
            ),
            new IsEqual<>(SimpleApiFormat.HTML)
        );
    }

    @Test
    void wildcardDefaultsToHtml() {
        MatcherAssert.assertThat(
            SimpleApiFormat.fromHeaders(
                Headers.from(new Header("Accept", "*/*"))
            ),
            new IsEqual<>(SimpleApiFormat.HTML)
        );
    }

    @Test
    void textHtmlDefaultsToHtml() {
        MatcherAssert.assertThat(
            SimpleApiFormat.fromHeaders(
                Headers.from(new Header("Accept", "text/html"))
            ),
            new IsEqual<>(SimpleApiFormat.HTML)
        );
    }

    @Test
    void jsonContentTypeIsCorrect() {
        MatcherAssert.assertThat(
            SimpleApiFormat.JSON.contentType(),
            new IsEqual<>("application/vnd.pypi.simple.v1+json")
        );
    }

    @Test
    void htmlContentTypeIsCorrect() {
        MatcherAssert.assertThat(
            SimpleApiFormat.HTML.contentType(),
            new IsEqual<>("text/html")
        );
    }

    @Test
    void honoursQualityValuesPreferringHtml() {
        MatcherAssert.assertThat(
            SimpleApiFormat.fromHeaders(
                Headers.from(
                    new Header(
                        "Accept",
                        "text/html;q=0.5, application/vnd.pypi.simple.v1+json;q=0.1"
                    )
                )
            ),
            new IsEqual<>(SimpleApiFormat.HTML)
        );
    }

    @Test
    void latestJsonAliasSelectsJson() {
        MatcherAssert.assertThat(
            SimpleApiFormat.fromHeaders(
                Headers.from(new Header("Accept", "application/vnd.pypi.simple.latest+json"))
            ),
            new IsEqual<>(SimpleApiFormat.JSON)
        );
    }

    @Test
    void latestHtmlAliasSelectsHtml() {
        MatcherAssert.assertThat(
            SimpleApiFormat.fromHeaders(
                Headers.from(
                    new Header(
                        "Accept",
                        "application/vnd.pypi.simple.latest+html, "
                            + "application/vnd.pypi.simple.v1+json;q=0.2"
                    )
                )
            ),
            new IsEqual<>(SimpleApiFormat.HTML)
        );
    }

    @Test
    void zeroQualityJsonIsNotAcceptable() {
        MatcherAssert.assertThat(
            SimpleApiFormat.fromHeaders(
                Headers.from(
                    new Header("Accept", "application/vnd.pypi.simple.v1+json;q=0, */*")
                )
            ),
            new IsEqual<>(SimpleApiFormat.HTML)
        );
    }

    @Test
    void pipDefaultAcceptSelectsJson() {
        MatcherAssert.assertThat(
            SimpleApiFormat.fromHeaders(
                Headers.from(
                    new Header(
                        "Accept",
                        "application/vnd.pypi.simple.v1+json, "
                            + "application/vnd.pypi.simple.v1+html; q=0.1, text/html; q=0.01"
                    )
                )
            ),
            new IsEqual<>(SimpleApiFormat.JSON)
        );
    }
}
