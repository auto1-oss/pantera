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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * Tests for {@link MetadataUrlRewriter#rewriteRoot}.
 *
 * <p>WS4-composer.2: every top-level URL field a Composer repository
 * root document advertises must be rewritten to a Pantera-local
 * equivalent (or dropped) — never passed through to the upstream
 * verbatim, since a client follows those fields directly and would
 * otherwise bypass Pantera's cache / cooldown / auth.</p>
 */
final class MetadataUrlRewriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BASE_URL = "https://pantera.example/php_proxy";

    @Test
    void rewritesMetadataUrlAndProvidersUrlToSameP2Template() throws Exception {
        final String json = """
            {
              "metadata-url": "https://repo.packagist.org/p2/%package%.json",
              "providers-url": "https://repo.packagist.org/p/%package%$%hash%.json"
            }
            """;
        final JsonNode node = rewrite(json);
        MatcherAssert.assertThat(
            "metadata-url rewritten to Pantera-local p2 template",
            node.get("metadata-url").asText(),
            new IsEqual<>(BASE_URL + "/p2/%package%.json")
        );
        MatcherAssert.assertThat(
            "providers-url rewritten to the same Pantera-local p2 template",
            node.get("providers-url").asText(),
            new IsEqual<>(BASE_URL + "/p2/%package%.json")
        );
    }

    @Test
    void rewritesAvailablePackagesUrl() throws Exception {
        final String json = """
            {"available-packages-url": "https://packagist.org/packages/list.json?fields[]=name"}
            """;
        final JsonNode node = rewrite(json);
        MatcherAssert.assertThat(
            node.get("available-packages-url").asText(),
            new IsEqual<>(BASE_URL + "/p2/available-packages.json")
        );
    }

    @Test
    void rewritesSearchWithPackagistQueryShape() throws Exception {
        final String json = """
            {"search": "https://packagist.org/search.json?q=%query%&type=%type%"}
            """;
        final JsonNode node = rewrite(json);
        MatcherAssert.assertThat(
            node.get("search").asText(),
            new IsEqual<>(BASE_URL + "/packages/list.json?q=%query%&type=%type%")
        );
    }

    @Test
    void rewritesList() throws Exception {
        final String json = """
            {"list": "https://packagist.org/packages/list.json"}
            """;
        final JsonNode node = rewrite(json);
        MatcherAssert.assertThat(
            node.get("list").asText(), new IsEqual<>(BASE_URL + "/packages/list.json")
        );
    }

    @Test
    void dropsNotifyAndNotifyBatch() throws Exception {
        final String json = """
            {
              "notify": "https://packagist.org/downloads/%package%",
              "notify-batch": "https://packagist.org/downloads/"
            }
            """;
        final JsonNode node = rewrite(json);
        MatcherAssert.assertThat(
            "notify dropped outright", node.has("notify"), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "notify-batch dropped outright", node.has("notify-batch"), new IsEqual<>(false)
        );
    }

    @Test
    void rewritesSecurityAdvisoriesApiUrlPreservingSiblingFields() throws Exception {
        final String json = """
            {
              "security-advisories": {
                "metadata": true,
                "api-url": "https://packagist.org/api/security-advisories/"
              }
            }
            """;
        final JsonNode node = rewrite(json);
        MatcherAssert.assertThat(
            "api-url rewritten to Pantera-local",
            node.get("security-advisories").get("api-url").asText(),
            new IsEqual<>(BASE_URL + "/api/security-advisories/")
        );
        MatcherAssert.assertThat(
            "sibling field preserved unchanged",
            node.get("security-advisories").get("metadata").asBoolean(),
            new IsEqual<>(true)
        );
    }

    @Test
    void securityAdvisoriesDisabledFlagPassesThroughUnchanged() throws Exception {
        // Composer allows "security-advisories": false to disable the feature.
        final String json = """
            {"security-advisories": false}
            """;
        final JsonNode node = rewrite(json);
        MatcherAssert.assertThat(
            node.get("security-advisories").asBoolean(), new IsEqual<>(false)
        );
    }

    @Test
    void dropsUnrecognisedTopLevelAbsoluteUrlFailClosed() throws Exception {
        final String json = """
            {"providers-lazy-url": "https://repo.packagist.org/p2/%package%.json"}
            """;
        final JsonNode node = rewrite(json);
        MatcherAssert.assertThat(node.has("providers-lazy-url"), new IsEqual<>(false));
    }

    @Test
    void passesThroughNonUrlTopLevelFieldsUnchanged() throws Exception {
        final String json = """
            {"warning": "some informational text", "warning-versions": ">=1.0"}
            """;
        final JsonNode node = rewrite(json);
        MatcherAssert.assertThat(
            "non-URL field passed through",
            node.get("warning").asText(), new IsEqual<>("some informational text")
        );
        MatcherAssert.assertThat(
            "non-URL field passed through",
            node.get("warning-versions").asText(), new IsEqual<>(">=1.0")
        );
    }

    @Test
    void rewritesDistUrlInsideInlinePackages() throws Exception {
        // Satis-style root with an inline packages map: dist.url must be
        // rewritten the same way the per-package endpoint already does —
        // otherwise a Satis-shaped root would leak dist URLs even after
        // the top-level rewrite.
        final String json = """
            {
              "packages": {
                "acme/foo": {
                  "1.0.0": {
                    "name": "acme/foo",
                    "version": "1.0.0",
                    "dist": {"type": "zip", "url": "https://github.com/acme/foo/archive/1.0.0.zip"}
                  }
                }
              }
            }
            """;
        final JsonNode node = rewrite(json);
        final JsonNode dist = node.get("packages").get("acme/foo").get("1.0.0").get("dist");
        MatcherAssert.assertThat(
            "dist.url rewritten to a Pantera-local dist path",
            dist.get("url").asText(), new IsEqual<>(BASE_URL + "/dist/acme/foo/1.0.0.zip")
        );
        MatcherAssert.assertThat(
            "original upstream URL preserved for ProxyDownloadSlice",
            dist.get("original_url").asText(),
            new IsEqual<>("https://github.com/acme/foo/archive/1.0.0.zip")
        );
    }

    @Test
    void packagesRewriteIsIdempotent() throws Exception {
        // A group member's packages.json has already been rewritten once
        // (by its own proxy root handler) by the time ComposerGroupSlice
        // calls rewriteRoot again — dist.url must not be double-rewritten.
        final String once = new String(
            new MetadataUrlRewriter(BASE_URL).rewriteRoot(
                """
                {
                  "packages": {
                    "acme/foo": {
                      "1.0.0": {
                        "dist": {"type": "zip", "url": "https://github.com/acme/foo/1.0.0.zip"}
                      }
                    }
                  }
                }
                """,
                BASE_URL
            ),
            StandardCharsets.UTF_8
        );
        final String groupBase = "/test_prefix/php-group";
        final JsonNode twice = MAPPER.readTree(
            new MetadataUrlRewriter(groupBase).rewriteRoot(once, groupBase)
        );
        final JsonNode dist = twice.get("packages").get("acme/foo").get("1.0.0").get("dist");
        // Unchanged by the second pass — still anchored to the first
        // rewriter's base, not double-prefixed or reset to the group base.
        MatcherAssert.assertThat(
            "dist.url not double-rewritten on a second rewriteRoot pass",
            dist.get("url").asText(), new IsEqual<>(BASE_URL + "/dist/acme/foo/1.0.0.zip")
        );
        MatcherAssert.assertThat(
            "original_url unchanged on a second rewriteRoot pass",
            dist.get("original_url").asText(),
            new IsEqual<>("https://github.com/acme/foo/1.0.0.zip")
        );
    }

    @Test
    void noFieldValueLeaksUpstreamHostAcrossFullPackagistShapedRoot() throws Exception {
        final String json = """
            {
              "packages": [],
              "notify": "https://packagist.org/downloads/%package%",
              "notify-batch": "https://packagist.org/downloads/",
              "providers-url": "https://repo.packagist.org/p/%package%$%hash%.json",
              "list": "https://packagist.org/packages/list.json",
              "search": "https://packagist.org/search.json?q=%query%&type=%type%",
              "metadata-url": "https://repo.packagist.org/p2/%package%.json",
              "available-packages-url": "https://packagist.org/packages/list.json?fields[]=name",
              "security-advisories": {
                "metadata": true,
                "api-url": "https://packagist.org/api/security-advisories/"
              }
            }
            """;
        final byte[] rewritten = new MetadataUrlRewriter(BASE_URL).rewriteRoot(json, BASE_URL);
        final String raw = new String(rewritten, StandardCharsets.UTF_8);
        MatcherAssert.assertThat(raw, new IsNot<>(new StringContains(false, "packagist.org")));
    }

    private static JsonNode rewrite(final String json) throws Exception {
        final byte[] rewritten = new MetadataUrlRewriter(BASE_URL).rewriteRoot(json, BASE_URL);
        return MAPPER.readTree(rewritten);
    }
}
