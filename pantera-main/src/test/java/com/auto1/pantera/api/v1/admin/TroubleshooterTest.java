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

import com.auto1.pantera.http.log.RequestTrace;
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
import org.hamcrest.core.StringContains;
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
        final FakeFetch fetch = new FakeFetch().traced(
            "npm_group", "/lodash/-/lodash-4.17.21.tgz", 404,
            new RequestTrace.Step("npm_group", "npm-group", 404, "negative_cache", 1L)
        );
        final JsonObject report = this.troubleshooter(fetch, negative, CooldownLookup.NONE)
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
            "the verdict names the negative cache of the group",
            report.getJsonObject("verdict").getString("summary"),
            new StringContains("negative cache of npm_group")
        );
        MatcherAssert.assertThat(
            "the entry that was there before the check is left alone",
            negative.inL1(new NegativeCacheKey(
                "npm_group", "npm-group", "lodash", "4.17.21/lodash-4.17.21.tgz"
            )),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "no member was asked",
            TroubleshooterTest.check(report, "group-walk").getString("message"),
            new StringContains("No member was asked")
        );
    }

    @Test
    void doesNotBlameACachedNotFoundTheCheckItselfCreated() throws Exception {
        // Replaying a request for a file the upstream lacks caches the 404,
        // as any client request would. That entry is no finding: it was not
        // there when the client's request failed.
        final NegativeCache negative = new NegativeCache(new NegativeCacheConfig());
        final NegativeCacheKey group = new NegativeCacheKey(
            "npm_group", "npm-group", "lodash", "9.9.9/lodash-9.9.9.tgz"
        );
        final NegativeCacheKey proxy = new NegativeCacheKey(
            "npm_proxy", "npm-proxy", "lodash", "9.9.9/lodash-9.9.9.tgz"
        );
        final FakeFetch fetch = new FakeFetch()
            .traced(
                "npm_group", "/lodash/-/lodash-9.9.9.tgz", 404,
                new RequestTrace.Step("npm_proxy", "npm-proxy", 404, "upstream_status_404", 120L),
                new RequestTrace.Step("npm_group", "npm-group", 404, "upstream_status_404", 125L)
            )
            .onFetch(target -> {
                if (target.endsWith("lodash-9.9.9.tgz")) {
                    negative.cacheNotFound(group);
                    negative.cacheNotFound(proxy);
                }
            });
        final JsonObject report = this.troubleshooter(fetch, negative, CooldownLookup.NONE)
            .explain("/npm_group/lodash/-/lodash-9.9.9.tgz", "Bearer t")
            .get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "no negative-cache problem is reported",
            TroubleshooterTest.statuses(report, "negative-cache"), new IsEqual<>(List.of("ok"))
        );
        MatcherAssert.assertThat(
            "the verdict is what the upstream said",
            report.getJsonObject("verdict").getString("summary"),
            new StringContains("none of the repositories asked has this file")
        );
        MatcherAssert.assertThat(
            "the check leaves no cached 404 behind",
            negative.inL1(group) || negative.inL1(proxy), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the group's own step speaks of its members, a group has no upstream",
            report.getJsonArray("trace").getJsonObject(1).getString("explanation"),
            new IsEqual<>("no member has this file")
        );
        MatcherAssert.assertThat(
            "and so does the line about the request",
            TroubleshooterTest.check(report, "request").getString("message"),
            new IsEqual<>("Request answers 404: npm_group: no member has this file")
        );
        MatcherAssert.assertThat(
            "the request was replayed once, not once more per member",
            fetch.seen().stream().filter(seen -> seen.contains("lodash-9.9.9.tgz")).count(),
            new IsEqual<>(1L)
        );
    }

    @Test
    void tracesWhatEveryRepositoryAnsweredAndWhy() throws Exception {
        final FakeFetch fetch = new FakeFetch().traced(
            "npm_group", "/lodash/-/lodash-9.9.9.tgz", 502,
            new RequestTrace.Step("npm_proxy", "npm-proxy", 502, "connection_refused", 4L),
            new RequestTrace.Step("npm_group", "npm-group", 502, "connection_refused", 6L)
        );
        final JsonObject report = this.troubleshooter(
            fetch, new NegativeCache(new NegativeCacheConfig()), CooldownLookup.NONE
        ).explain("/npm_group/lodash/-/lodash-9.9.9.tgz", "Bearer t").get(10, TimeUnit.SECONDS);
        final JsonArray trace = report.getJsonArray("trace");
        MatcherAssert.assertThat(
            "one step per repository, the member first",
            List.of(
                trace.getJsonObject(0).getString("repository"),
                trace.getJsonObject(1).getString("repository")
            ),
            new IsEqual<>(List.of("npm_proxy", "npm_group"))
        );
        MatcherAssert.assertThat(
            "the member's step says how its upstream call failed",
            trace.getJsonObject(0).getString("explanation"),
            new StringContains("refused the connection")
        );
        MatcherAssert.assertThat(
            "the member's step is marked as the one a proxy answers for",
            trace.getJsonObject(0).getString("mode"), new IsEqual<>("proxy")
        );
        MatcherAssert.assertThat(
            "the group's step names the member that failed and how",
            trace.getJsonObject(1).getString("explanation"),
            new IsEqual<>("member npm_proxy: its upstream refused the connection")
        );
        MatcherAssert.assertThat(
            "the verdict names the member and the failure",
            report.getJsonObject("verdict").getString("summary"),
            new StringContains("npm_proxy")
        );
        MatcherAssert.assertThat(
            "and it is a problem",
            report.getJsonObject("verdict").getString("status"), new IsEqual<>("problem")
        );
    }

    @Test
    void namesAMemberThatARoutingRuleKeptFromBeingAsked() throws Exception {
        // The step of a skipped member comes from the group, which knows
        // the member's name only: its type is taken from the repositories.
        final FakeFetch fetch = new FakeFetch().traced(
            "npm_group", "/lodash/-/lodash-9.9.9.tgz", 404,
            new RequestTrace.Step("npm_proxy", "", 404, "skipped_by_routing_rule", 0L),
            new RequestTrace.Step("npm_group", "npm-group", 404, "skipped_by_routing_rule", 1L)
        );
        final JsonObject step = this.troubleshooter(
            fetch, new NegativeCache(new NegativeCacheConfig()), CooldownLookup.NONE
        ).explain("/npm_group/lodash/-/lodash-9.9.9.tgz", "Bearer t").get(10, TimeUnit.SECONDS)
            .getJsonArray("trace").getJsonObject(0);
        MatcherAssert.assertThat(
            "the step says the member was not asked, and why",
            step.getString("explanation"),
            new IsEqual<>("was not asked: a routing rule of the group limits it to other paths")
        );
        MatcherAssert.assertThat(
            "and carries the type of the repository",
            step.getString("type"), new IsEqual<>("npm-proxy")
        );
    }

    @Test
    void saysWhichMemberServedTheRequest() throws Exception {
        final FakeFetch fetch = new FakeFetch().traced(
            "npm_group", "/lodash/-/lodash-4.17.21.tgz", 200,
            new RequestTrace.Step("npm_proxy", "npm-proxy", 200, null, 30L),
            new RequestTrace.Step("npm_group", "npm-group", 200, null, 31L)
        );
        final JsonObject report = this.troubleshooter(
            fetch, new NegativeCache(new NegativeCacheConfig()), CooldownLookup.NONE
        ).explain("/npm_group/lodash/-/lodash-4.17.21.tgz", "Bearer t").get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the verdict is fine",
            report.getJsonObject("verdict").getString("status"), new IsEqual<>("ok")
        );
        MatcherAssert.assertThat(
            "and names the member that served",
            report.getJsonObject("verdict").getString("summary"),
            new StringContains("served by npm_proxy")
        );
        MatcherAssert.assertThat(
            "the group's step says which member served",
            report.getJsonArray("trace").getJsonObject(1).getString("explanation"),
            new IsEqual<>("served by member npm_proxy")
        );
    }

    @Test
    void explainsARequestRefusedForLackOfAPermit() throws Exception {
        final FakeFetch fetch = new FakeFetch().traced(
            "npm_proxy", "/lodash/-/lodash-4.17.21.tgz", 503,
            new RequestTrace.Step("npm_proxy", "npm-proxy", 503, "bulkhead_full", 20_000L)
        );
        final JsonObject report = this.troubleshooter(
            fetch, new NegativeCache(new NegativeCacheConfig()), CooldownLookup.NONE
        ).explain("/npm_proxy/lodash/-/lodash-4.17.21.tgz", "Bearer t").get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the verdict says the repository had no free permit",
            report.getJsonObject("verdict").getString("summary"),
            new StringContains("no free permit")
        );
        MatcherAssert.assertThat(
            "and points at the setting",
            report.getJsonObject("verdict").getString("advice"),
            new StringContains("Bulkhead")
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
     * Statuses of the checks of a layer.
     *
     * @param report Report
     * @param layer Layer
     * @return Statuses in order
     */
    private static List<String> statuses(final JsonObject report, final String layer) {
        final JsonArray checks = report.getJsonArray("checks");
        final List<String> out = new java.util.ArrayList<>();
        for (int idx = 0; idx < checks.size(); idx = idx + 1) {
            if (layer.equals(checks.getJsonObject(idx).getString("layer"))) {
                out.add(checks.getJsonObject(idx).getString("status"));
            }
        }
        return out;
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
