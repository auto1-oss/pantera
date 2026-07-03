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
package com.auto1.pantera.composer.cooldown;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link ComposerRootPackagesFilter}.
 *
 * @since 2.2.0
 */
final class ComposerRootPackagesFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ComposerRootPackagesFilter filter;

    @BeforeEach
    void setUp() {
        this.filter = new ComposerRootPackagesFilter();
    }

    @Test
    void removesBlockedVersionFromInlineObjectShape() throws Exception {
        final String json = """
            {
              "packages": {
                "acme/foo": {
                  "1.0.0": {"name": "acme/foo", "version": "1.0.0"},
                  "1.1.0": {"name": "acme/foo", "version": "1.1.0"},
                  "2.0.0": {"name": "acme/foo", "version": "2.0.0"}
                }
              },
              "notify-batch": "/downloads/"
            }
            """;
        final JsonNode root = MAPPER.readTree(json);
        final JsonNode filtered = this.filter.filter(
            root,
            (pkg, ver) -> "acme/foo".equals(pkg) && "2.0.0".equals(ver)
        );
        final JsonNode versions = filtered.get("packages").get("acme/foo");
        assertThat(versions.has("1.0.0"), is(true));
        assertThat(versions.has("1.1.0"), is(true));
        assertThat(versions.has("2.0.0"), is(false));
        // Top-level metadata preserved.
        assertThat(filtered.get("notify-batch").asText(), equalTo("/downloads/"));
    }

    @Test
    void dropsPackageWhenAllVersionsBlocked() throws Exception {
        final String json = """
            {
              "packages": {
                "acme/foo": {
                  "1.0.0": {"name": "acme/foo"},
                  "2.0.0": {"name": "acme/foo"}
                },
                "acme/bar": {
                  "1.0.0": {"name": "acme/bar"}
                }
              }
            }
            """;
        final JsonNode root = MAPPER.readTree(json);
        final JsonNode filtered = this.filter.filter(
            root,
            (pkg, ver) -> "acme/foo".equals(pkg)
        );
        // acme/foo fully dropped.
        assertThat(filtered.get("packages").has("acme/foo"), is(false));
        // acme/bar untouched.
        assertThat(filtered.get("packages").has("acme/bar"), is(true));
    }

    @Test
    void preservesLazyProvidersScheme() throws Exception {
        // Typical Packagist v2 root: no inline packages, only
        // providers-url / metadata-url templates.
        final String json = """
            {
              "packages": [],
              "providers-url": "/p/%package%$%hash%.json",
              "metadata-url": "/p2/%package%.json",
              "search": "https://packagist.org/search.json?q=%query%",
              "notify-batch": "https://packagist.org/downloads/"
            }
            """;
        final JsonNode root = MAPPER.readTree(json);
        final JsonNode filtered = this.filter.filter(
            root,
            (pkg, ver) -> true // would block everything if applied
        );
        // Lazy scheme: filter passes metadata through unchanged.
        assertThat(filtered.get("metadata-url").asText(),
            equalTo("/p2/%package%.json"));
        assertThat(filtered.get("providers-url").asText(),
            equalTo("/p/%package%$%hash%.json"));
    }

    @Test
    void preservesProvidersUrlSchemeWithEmptyPackagesObject() throws Exception {
        // Some repos emit packages:{} plus the provider template; also
        // a lazy scheme we should pass through unchanged.
        final String json = """
            {
              "packages": {},
              "metadata-url": "/p2/%package%.json"
            }
            """;
        final JsonNode root = MAPPER.readTree(json);
        final JsonNode filtered = this.filter.filter(
            root,
            (pkg, ver) -> true
        );
        assertThat(filtered.get("metadata-url").asText(),
            equalTo("/p2/%package%.json"));
        assertThat(filtered.get("packages").size(), equalTo(0));
    }

    @Test
    void noOpWhenNothingBlocked() throws Exception {
        final String json = """
            {
              "packages": {
                "acme/foo": {
                  "1.0.0": {}, "2.0.0": {}
                }
              }
            }
            """;
        final JsonNode root = MAPPER.readTree(json);
        final JsonNode filtered = this.filter.filter(root, (pkg, ver) -> false);
        final JsonNode versions = filtered.get("packages").get("acme/foo");
        assertThat(versions.has("1.0.0"), is(true));
        assertThat(versions.has("2.0.0"), is(true));
    }

    @Test
    void filtersInlineArrayOfVersionObjectsShape() throws Exception {
        // Packagist v1 array-of-version-objects shape — each element
        // is {name, version, ...}. Block by version field.
        final String json = """
            {
              "packages": {
                "acme/foo": [
                  {"name": "acme/foo", "version": "1.0.0"},
                  {"name": "acme/foo", "version": "1.1.0"},
                  {"name": "acme/foo", "version": "2.0.0"}
                ]
              }
            }
            """;
        final JsonNode root = MAPPER.readTree(json);
        final JsonNode filtered = this.filter.filter(
            root,
            (pkg, ver) -> "2.0.0".equals(ver)
        );
        final JsonNode arr = filtered.get("packages").get("acme/foo");
        assertThat(arr.isArray(), is(true));
        assertThat(arr.size(), equalTo(2));
    }

    @Test
    void extractVersionsObjectShape() throws Exception {
        final String json = """
            {
              "packages": {
                "acme/foo": {
                  "1.0.0": {}, "2.0.0": {}
                },
                "acme/bar": {
                  "3.0.0": {}
                }
              }
            }
            """;
        final JsonNode root = MAPPER.readTree(json);
        final List<ComposerRootPackagesFilter.PackageVersion> entries =
            this.filter.extractPackageVersions(root);
        assertThat(entries, hasSize(3));
        final Set<String> pairs = new java.util.HashSet<>();
        for (final ComposerRootPackagesFilter.PackageVersion pv : entries) {
            pairs.add(pv.pkg() + "@" + pv.version());
        }
        assertThat(pairs, equalTo(Set.of(
            "acme/foo@1.0.0", "acme/foo@2.0.0", "acme/bar@3.0.0"
        )));
    }

    @Test
    void extractVersionsLazySchemeReturnsEmpty() throws Exception {
        final String json = """
            {
              "packages": [],
              "metadata-url": "/p2/%package%.json"
            }
            """;
        final JsonNode root = MAPPER.readTree(json);
        assertThat(
            this.filter.extractPackageVersions(root),
            hasSize(0)
        );
    }

    @Test
    void dropsAllPackagesWhenAllBlocked() throws Exception {
        final String json = """
            {
              "packages": {
                "acme/foo": {"1.0.0": {}},
                "acme/bar": {"2.0.0": {}}
              },
              "metadata-url": "/p2/%package%.json"
            }
            """;
        final JsonNode root = MAPPER.readTree(json);
        final BiPredicate<String, String> allBlocked = (pkg, ver) -> true;
        final JsonNode filtered = this.filter.filter(root, allBlocked);
        // All packages stripped; metadata preserved.
        assertThat(filtered.get("packages").size(), equalTo(0));
        assertThat(filtered.get("metadata-url").asText(),
            equalTo("/p2/%package%.json"));
    }

    @Test
    void handlesMissingPackagesKey() throws Exception {
        final String json = """
            {
              "metadata-url": "/p2/%package%.json",
              "notify-batch": "/downloads/"
            }
            """;
        final JsonNode root = MAPPER.readTree(json);
        final JsonNode filtered = this.filter.filter(root, (pkg, ver) -> true);
        // No packages to filter; structure intact.
        assertThat(filtered.get("metadata-url").asText(),
            equalTo("/p2/%package%.json"));
        assertThat(filtered.has("packages"), is(false));
    }
}
