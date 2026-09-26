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

/**
 * {@link Troubleshooter}: layered explanation of a failing request with
 * one-click fixes.
 *
 * @since 2.2.9
 */
final class TroubleshooterTest {

    /**
     * Topology: npm group over a proxy.
     */
    private final FakeTopology topology = new FakeTopology()
        .proxy("npm_proxy", "npm-proxy")
        .group("npm_group", "npm-group", "npm_proxy");

    @Test
    void explainsAShadowingNegativeCacheEntryWithAFix() throws Exception {
        final NegativeCache negative = new NegativeCache(new NegativeCacheConfig());
        negative.cacheNotFound(new NegativeCacheKey(
            "npm_group", "npm-group", "lodash", "4.17.21/lodash-4.17.21.tgz"
        ));
        final JsonObject report = this.troubleshooter(new FakeFetch(), negative, CooldownLookup.NONE)
            .explain("http://h/prefix/api/npm_group/lodash/-/lodash-4.17.21.tgz", "Bearer t")
            .get(10, TimeUnit.SECONDS);
        final JsonObject check = TroubleshooterTest.check(report, "negative-cache:npm_group");
        MatcherAssert.assertThat("problem", check.getString("status"), new IsEqual<>("problem"));
        MatcherAssert.assertThat(
            "the fix invalidates exactly that key",
            check.getJsonObject("fix"),
            new IsEqual<>(new JsonObject()
                .put("action", "invalidate")
                .put("endpoint", "/api/v1/admin/neg-cache/invalidate")
                .put("body", new JsonObject()
                    .put("scope", "npm_group").put("repoType", "npm-group")
                    .put("artifactName", "lodash")
                    .put("version", "4.17.21/lodash-4.17.21.tgz")))
        );
        MatcherAssert.assertThat(
            "parsed package and version",
            List.of(
                report.getJsonObject("parsed").getString("package"),
                report.getJsonObject("parsed").getString("version"),
                report.getJsonObject("parsed").getString("kind")
            ),
            new IsEqual<>(List.of("lodash", "4.17.21", "artifact"))
        );
        MatcherAssert.assertThat(
            "the group walk records the member's answer",
            TroubleshooterTest.check(report, "group-walk").getJsonArray("members")
                .getJsonObject(0).getInteger("status"),
            new IsEqual<>(404)
        );
    }

    @Test
    void offersAnUnblockForABlockedVersion() throws Exception {
        final FakeFetch fetch = new FakeFetch()
            .answer("npm_proxy", "/openai", 200, "{\"versions\":{\"1.0.0\":{}}}");
        final CooldownLookup rows = (repos, names) -> List.of(new CooldownPackageRow(
            "npm-proxy", "npm_proxy", "openai", "2.0.0", "blocked",
            Instant.now().plus(1, ChronoUnit.DAYS), "FRESH_RELEASE", true, null
        ));
        final JsonObject report = this.troubleshooter(
            fetch, new NegativeCache(new NegativeCacheConfig()), rows
        ).explain("/npm_proxy/openai/-/openai-2.0.0.tgz", null).get(10, TimeUnit.SECONDS);
        final JsonObject check = TroubleshooterTest.check(report, "cooldown");
        MatcherAssert.assertThat("problem", check.getString("status"), new IsEqual<>("problem"));
        MatcherAssert.assertThat(
            "the fix is the existing unblock endpoint of the blocking repository",
            check.getJsonObject("fix").getString("endpoint"),
            new IsEqual<>("/api/v1/repositories/npm_proxy/cooldown/unblock")
        );
    }

    @Test
    void attributesACooldownForbiddenToCooldownNotPermissions() throws Exception {
        final FakeFetch fetch = new FakeFetch()
            .answer("npm_proxy", "/openai", 200, "{\"versions\":{\"1.0.0\":{}}}")
            .answer(
                "npm_proxy", "/openai/-/openai-2.0.0.tgz", 403,
                "{\"error\":\"version in cooldown\",\"blocked_until\":\"2099-01-01T00:00:00Z\"}"
            );
        final CooldownLookup rows = (repos, names) -> List.of(new CooldownPackageRow(
            "npm-proxy", "npm_proxy", "openai", "2.0.0", "blocked",
            Instant.now().plus(1, ChronoUnit.DAYS), "FRESH_RELEASE", true, null
        ));
        final String message = TroubleshooterTest.check(
            this.troubleshooter(
                fetch, new NegativeCache(new NegativeCacheConfig()), rows
            ).explain("/npm_proxy/openai/-/openai-2.0.0.tgz", null).get(10, TimeUnit.SECONDS),
            "request"
        ).getString("message");
        MatcherAssert.assertThat(
            "a cooldown 403 is attributed to cooldown",
            message.contains("cooldown"), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "a cooldown 403 is not blamed on read permissions",
            message.contains("permission"), new IsEqual<>(false)
        );
    }

    @Test
    void offersARefreshForAReleasedButHiddenVersion() throws Exception {
        final FakeFetch fetch = new FakeFetch()
            .answer("npm_proxy", "/openai", 200, "{\"versions\":{\"1.0.0\":{}}}");
        final CooldownLookup rows = (repos, names) -> List.of(new CooldownPackageRow(
            "npm-proxy", "npm_proxy", "openai", "2.0.0", "released",
            Instant.now().plus(1, ChronoUnit.DAYS), "FRESH_RELEASE", true, Instant.now()
        ));
        final JsonObject report = this.troubleshooter(
            fetch, new NegativeCache(new NegativeCacheConfig()), rows
        ).explain("/npm_proxy/openai", null).get(10, TimeUnit.SECONDS);
        final JsonObject check = TroubleshooterTest.check(report, "cooldown");
        MatcherAssert.assertThat(
            "refresh-package fixes a stale listing",
            check.getJsonObject("fix").getString("action"), new IsEqual<>("refresh-package")
        );
        MatcherAssert.assertThat(
            "metadata request",
            report.getJsonObject("parsed").getString("kind"), new IsEqual<>("metadata")
        );
    }

    @Test
    void reportsAnUnknownRepositoryAsAProblem() throws Exception {
        final JsonObject report = this.troubleshooter(
            new FakeFetch(), new NegativeCache(new NegativeCacheConfig()), CooldownLookup.NONE
        ).explain("/nope/lodash", null).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "no repository",
            report.getValue("repo"), new IsEqual<>(null)
        );
        MatcherAssert.assertThat(
            "one repository problem",
            report.getJsonArray("checks").getJsonObject(0).getString("status"),
            new IsEqual<>("problem")
        );
    }

    /**
     * Troubleshooter over the topology.
     *
     * @param fetch Fetch
     * @param negative Negative cache
     * @param rows Cooldown rows
     * @return Troubleshooter
     */
    private Troubleshooter troubleshooter(
        final FakeFetch fetch, final NegativeCache negative, final CooldownLookup rows
    ) {
        final AdminDiagnostics diag = new AdminDiagnostics(
            this.topology, fetch, BreakerProbe.NONE, "node-1"
        );
        return new Troubleshooter(
            diag, negative, new PackageInspector(diag, negative, Optional::empty, rows)
        );
    }

    /**
     * Check by id.
     *
     * @param report Report
     * @param id Id
     * @return Check
     */
    private static JsonObject check(final JsonObject report, final String id) {
        final JsonArray checks = report.getJsonArray("checks");
        for (int idx = 0; idx < checks.size(); idx = idx + 1) {
            if (id.equals(checks.getJsonObject(idx).getString("id"))) {
                return checks.getJsonObject(idx);
            }
        }
        throw new AssertionError("no check " + id + " in " + report.encodePrettily());
    }
}
