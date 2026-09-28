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
package com.auto1.pantera.api.v1.admin;

import com.auto1.pantera.cache.NegativeCacheConfig;
import com.auto1.pantera.cooldown.CooldownPackageRow;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.cache.NegativeCacheKey;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link PackageInspector}: per-version cooldown state against what each
 * repository serves, reproducing the "unblocked but still invisible" bug.
 *
 * @since 2.2.9
 */
final class PackageInspectorTest {

    /**
     * Topology: an npm group over one proxy.
     */
    private final FakeTopology topology = new FakeTopology()
        .proxy("npm_proxy", "npm-proxy")
        .group("npm_group", "npm-group", "npm_proxy");

    @Test
    void flagsAReleasedVersionTheProxyStillHides() throws Exception {
        final FakeFetch fetch = new FakeFetch()
            .answer("npm_proxy", "/openai", 200, "{\"versions\":{\"1.0.0\":{}}}")
            .answer("npm_group", "/openai", 200, "{\"versions\":{\"1.0.0\":{}}}");
        final CooldownLookup rows = (repos, names) -> List.of(new CooldownPackageRow(
            "npm-proxy", "npm_proxy", "openai", "2.0.0", "released",
            Instant.now().plus(1, ChronoUnit.DAYS), "FRESH_RELEASE", true, Instant.now()
        ));
        final JsonObject doc = this.inspector(fetch, rows)
            .inspect("npm", "openai", null, "Bearer t").get(10, TimeUnit.SECONDS);
        final JsonObject two = PackageInspectorTest.version(doc, "2.0.0");
        MatcherAssert.assertThat(
            "state comes from the live row",
            two.getJsonObject("cooldown").getString("state"), new IsEqual<>("released")
        );
        MatcherAssert.assertThat(
            "released yet hidden where it was blocked is a mismatch",
            two.getBoolean("mismatch"), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "hidden in both repositories",
            two.getJsonArray("hiddenIn"), new IsEqual<>(new JsonArray(List.of("npm_group", "npm_proxy")))
        );
        MatcherAssert.assertThat(
            "listings were fetched with the caller's credentials",
            fetch.seen().contains("npm_proxy /openai Bearer t"), new IsEqual<>(true)
        );
    }

    @Test
    void blockedAndHiddenIsConsistent() throws Exception {
        final FakeFetch fetch = new FakeFetch()
            .answer("npm_proxy", "/openai", 200, "{\"versions\":{\"1.0.0\":{}}}")
            .answer("npm_group", "/openai", 200, "{\"versions\":{\"1.0.0\":{}}}");
        final CooldownLookup rows = (repos, names) -> List.of(new CooldownPackageRow(
            "npm-proxy", "npm_proxy", "openai", "2.0.0", "blocked",
            Instant.now().plus(1, ChronoUnit.DAYS), "FRESH_RELEASE", true, null
        ));
        final JsonObject two = PackageInspectorTest.version(
            this.inspector(fetch, rows).inspect("npm", "openai", null, null)
                .get(10, TimeUnit.SECONDS),
            "2.0.0"
        );
        MatcherAssert.assertThat(
            "blocked and hidden agree",
            two.getBoolean("mismatch"), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "blockedUntil is reported",
            two.getJsonObject("cooldown").getString("blockedUntil") != null, new IsEqual<>(true)
        );
    }

    @Test
    void flagsAGroupHidingAVersionItsMemberLists() throws Exception {
        final FakeFetch fetch = new FakeFetch()
            .answer("npm_proxy", "/openai", 200, "{\"versions\":{\"1.0.0\":{},\"1.1.0\":{}}}")
            .answer("npm_group", "/openai", 200, "{\"versions\":{\"1.0.0\":{}}}");
        final JsonObject newer = PackageInspectorTest.version(
            this.inspector(fetch, CooldownLookup.NONE).inspect("npm", "openai", "npm_group", null)
                .get(10, TimeUnit.SECONDS),
            "1.1.0"
        );
        MatcherAssert.assertThat(
            "a stale group listing is a mismatch even without any cooldown record",
            newer.getBoolean("mismatch"), new IsEqual<>(true)
        );
    }

    @Test
    void reportsNegativeCacheEntriesPerRepository() throws Exception {
        final NegativeCache negative = new NegativeCache(new NegativeCacheConfig());
        negative.cacheNotFound(new NegativeCacheKey("npm_proxy", "npm-proxy", "openai", "9.9.9"));
        final JsonObject doc = new PackageInspector(
            new AdminDiagnostics(this.topology, new FakeFetch(), BreakerProbe.NONE, "node-1"),
            negative, Optional::empty, CooldownLookup.NONE
        ).inspect("npm", "openai", "npm_proxy", null).get(10, TimeUnit.SECONDS);
        final JsonObject repo = doc.getJsonArray("repos").getJsonObject(0);
        MatcherAssert.assertThat(
            "the proxy lists its cached 404",
            repo.getJsonArray("negativeCache").size(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the node is named",
            doc.getString("node"), new IsEqual<>("node-1")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ubuntu",
        "library/ubuntu",
        "ubuntu:latest",
        "docker_group/ubuntu",
        "localhost:8081/docker_group/ubuntu",
        "localhost:8081/test_prefix/api/docker_group/ubuntu:24.04",
        "http://localhost:8081/docker_group/library/ubuntu@sha256:da6fc2be5478",
    })
    void findsDockerBlocksWhateverTheImageSpelling(final String typed) throws Exception {
        final FakeTopology docker = new FakeTopology()
            .proxy("docker_proxy", "docker-proxy")
            .group("docker_group", "docker-group", "docker_proxy");
        final List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        final CooldownLookup rows = (repos, names) -> {
            asked.addAll(names);
            return List.of();
        };
        new PackageInspector(
            new AdminDiagnostics(docker, new FakeFetch(), BreakerProbe.NONE, "n"),
            new NegativeCache(new NegativeCacheConfig()), Optional::empty, rows
        ).inspect("docker", typed, null, null).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(asked.contains("library/ubuntu"), new IsEqual<>(true));
    }

    @Test
    void keepsANamespacedDockerImageOutOfLibrary() throws Exception {
        final FakeTopology docker = new FakeTopology().proxy("docker_proxy", "docker-proxy");
        final List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        new PackageInspector(
            new AdminDiagnostics(docker, new FakeFetch(), BreakerProbe.NONE, "n"),
            new NegativeCache(new NegativeCacheConfig()), Optional::empty,
            (repos, names) -> {
                asked.addAll(names);
                return List.of();
            }
        ).inspect("docker", "docker_proxy/myorg/app:1.0", null, null).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the namespaced image is looked up as typed",
            asked.contains("myorg/app"), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "a namespaced image never becomes an official library image",
            asked.contains("library/app"), new IsEqual<>(false)
        );
    }

    @Test
    void unsupportedFormatsSaySo() throws Exception {
        final FakeTopology docker = new FakeTopology().proxy("docker_proxy", "docker-proxy");
        final JsonObject doc = new PackageInspector(
            new AdminDiagnostics(docker, new FakeFetch(), BreakerProbe.NONE, "n"),
            new NegativeCache(new NegativeCacheConfig()), Optional::empty, CooldownLookup.NONE
        ).inspect("docker", "library/nginx", null, null).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            doc.getJsonArray("repos").getJsonObject(0).getJsonObject("metadata")
                .getBoolean("unsupported"),
            new IsEqual<>(true)
        );
    }

    /**
     * Inspector over the npm topology.
     *
     * @param fetch Fetch
     * @param rows Cooldown rows
     * @return Inspector
     */
    private PackageInspector inspector(final FakeFetch fetch, final CooldownLookup rows) {
        return new PackageInspector(
            new AdminDiagnostics(this.topology, fetch, BreakerProbe.NONE, "node-1"),
            new NegativeCache(new NegativeCacheConfig()), Optional::empty, rows
        );
    }

    /**
     * Version entry.
     *
     * @param doc Inspection
     * @param version Version
     * @return Entry
     */
    private static JsonObject version(final JsonObject doc, final String version) {
        final JsonArray versions = doc.getJsonArray("versions");
        for (int idx = 0; idx < versions.size(); idx = idx + 1) {
            if (version.equals(versions.getJsonObject(idx).getString("version"))) {
                return versions.getJsonObject(idx);
            }
        }
        throw new AssertionError("no version " + version + " in " + doc.encode());
    }
}
