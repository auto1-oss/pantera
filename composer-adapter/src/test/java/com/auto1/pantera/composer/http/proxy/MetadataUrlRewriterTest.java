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

package com.auto1.pantera.composer.http.proxy;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MetadataUrlRewriter}.
 */
final class MetadataUrlRewriterTest {

    @Test
    void alreadyRewrittenDistIsReRootedAndKeepsItsUpstreamUrl() {
        final JsonObject dist = MetadataUrlRewriterTest.rewrite(
            "https://b.example/php_proxy",
            "{\"packages\":{\"acme/lib\":{\"1.0\":{\"dist\":{\"type\":\"zip\","
                + "\"url\":\"https://a.example/php_proxy/dist/acme/lib/1.0.zip\","
                + "\"original_url\":\"https://up.example/lib-1.0.zip\"}}}}}"
        ).getJsonObject("packages").getJsonObject("acme/lib").getJsonObject("1.0").getJsonObject("dist");
        MatcherAssert.assertThat(
            "url follows the new base",
            dist.getString("url"),
            new IsEqual<>("https://b.example/php_proxy/dist/acme/lib/1.0.zip")
        );
        MatcherAssert.assertThat(
            "original_url is the upstream one, not the previous proxy URL",
            dist.getString("original_url"),
            new IsEqual<>("https://up.example/lib-1.0.zip")
        );
    }

    @Test
    void upstreamDistGetsAProxyUrlAndKeepsTheUpstreamOne() {
        final JsonObject dist = MetadataUrlRewriterTest.rewrite(
            "https://b.example/php_proxy",
            "{\"packages\":{\"acme/lib\":[{\"version\":\"2.0\",\"dist\":{\"type\":\"zip\","
                + "\"url\":\"https://up.example/lib-2.0.zip\"}}]}}"
        ).getJsonObject("packages").getJsonArray("acme/lib").getJsonObject(0).getJsonObject("dist");
        MatcherAssert.assertThat(
            "url points at the proxy",
            dist.getString("url"),
            new IsEqual<>("https://b.example/php_proxy/dist/acme/lib/2.0.zip")
        );
        MatcherAssert.assertThat(
            "original_url is recorded",
            dist.getString("original_url"),
            new IsEqual<>("https://up.example/lib-2.0.zip")
        );
    }

    /**
     * Rewrite a document.
     *
     * @param base Base URL
     * @param json Document
     * @return Rewritten document
     */
    private static JsonObject rewrite(final String base, final String json) {
        return Json.createReader(
            new StringReader(
                new String(new MetadataUrlRewriter(base).rewrite(json), StandardCharsets.UTF_8)
            )
        ).readObject();
    }
}
