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
package com.auto1.pantera.api.v1;

import com.auto1.pantera.settings.repo.RepoSummary;
import io.vertx.core.json.JsonObject;
import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.json.Json;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNull;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RepoListing}: filtering, sorting and projection of
 * repository summaries, without Vert.x.
 */
final class RepoListingTest {

    @Test
    void projectsEveryField() {
        final JsonObject npm = new RepoListing(fixture(), name -> true)
            .items(new RepoListing.Params(null, null, null, null, null))
            .stream().filter(o -> "npm-local".equals(o.getString("name"))).findFirst().get();
        MatcherAssert.assertThat("mode", npm.getString("mode"), new IsEqual<>("hosted"));
        MatcherAssert.assertThat("storage alias", npm.getString("storage"), new IsEqual<>("s3-main"));
        MatcherAssert.assertThat("anon read", npm.getBoolean("anonymous_read"), new IsEqual<>(true));
        MatcherAssert.assertThat("anon write", npm.getBoolean("anonymous_write"), new IsEqual<>(false));
        MatcherAssert.assertThat("immutable", npm.getBoolean("immutable"), new IsEqual<>(false));
        MatcherAssert.assertThat(
            "updated_at", npm.getString("updated_at"), new IsEqual<>("2026-10-03T00:00:00Z")
        );
        MatcherAssert.assertThat("updated_by", npm.getString("updated_by"), new IsEqual<>("ayd"));
    }

    @Test
    void groupHasNullStorageAndNullImmutable() {
        final JsonObject grp = new RepoListing(fixture(), name -> true)
            .items(new RepoListing.Params("all", null, null, null, null)).get(0);
        MatcherAssert.assertThat("mode", grp.getString("mode"), new IsEqual<>("group"));
        MatcherAssert.assertThat("storage", grp.getValue("storage"), new IsNull<>());
        MatcherAssert.assertThat("immutable", grp.getValue("immutable"), new IsNull<>());
        MatcherAssert.assertThat(
            "updated_by falls back to created_by",
            grp.getString("updated_by"), new IsEqual<>("bootstrap")
        );
    }

    @Test
    void filtersByModeAndTypeAndQueryAndPermission() {
        final List<String> proxies = names(new RepoListing(fixture(), name -> true)
            .items(new RepoListing.Params(null, null, "proxy", null, null)));
        MatcherAssert.assertThat("mode filter", proxies, new IsEqual<>(List.of("maven-central")));
        final List<String> maven = names(new RepoListing(fixture(), name -> true)
            .items(new RepoListing.Params(null, "maven", null, null, null)));
        MatcherAssert.assertThat(
            "type filter", maven, new IsEqual<>(List.of("all-maven", "maven-central"))
        );
        final List<String> denied = names(new RepoListing(fixture(), name -> !name.startsWith("npm"))
            .items(new RepoListing.Params("l", null, null, null, null)));
        MatcherAssert.assertThat(
            "query + permission", denied, new IsEqual<>(List.of("all-maven", "maven-central"))
        );
    }

    @Test
    void sortsByUpdatedDescWithNullsLast() {
        final List<String> order = names(new RepoListing(fixture(), name -> true)
            .items(new RepoListing.Params(null, null, null, "updated_at", "desc")));
        MatcherAssert.assertThat(
            order, new IsEqual<>(List.of("npm-local", "maven-central", "all-maven"))
        );
    }

    @Test
    void sortsByTypeThenName() {
        final List<String> order = names(new RepoListing(fixture(), name -> true)
            .items(new RepoListing.Params(null, null, null, "type", "asc")));
        MatcherAssert.assertThat(
            order, new IsEqual<>(List.of("all-maven", "maven-central", "npm-local"))
        );
    }

    @Test
    void rejectsUnknownParams() {
        MatcherAssert.assertThat(
            "mode",
            new RepoListing.Params(null, null, "virtual", null, null).validate().isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "sort",
            new RepoListing.Params(null, null, null, "size", null).validate().isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "order",
            new RepoListing.Params(null, null, null, null, "up").validate().isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "defaults ok",
            new RepoListing.Params(null, null, null, null, null).validate(),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void defaultNameOrderIgnoresCase() {
        final List<RepoSummary> rows = List.of(
            row("b-repo", "npm", "{\"type\":\"npm\"}", null),
            row("C-repo", "npm", "{\"type\":\"npm\"}", null),
            row("a-repo", "npm", "{\"type\":\"npm\"}", null)
        );
        final List<String> asc = names(new RepoListing(rows, name -> true)
            .items(new RepoListing.Params(null, null, null, null, null)));
        MatcherAssert.assertThat("asc", asc, new IsEqual<>(List.of("a-repo", "b-repo", "C-repo")));
        final List<String> desc = names(new RepoListing(rows, name -> true)
            .items(new RepoListing.Params(null, null, null, "name", "desc")));
        MatcherAssert.assertThat("desc", desc, new IsEqual<>(List.of("C-repo", "b-repo", "a-repo")));
    }

    @Test
    void legacyRowWithoutRepoWrapperIsListed() {
        final RepoSummary legacy = new RepoSummary(
            "old", "", Json.createObjectBuilder().add("type", "rpm").build(), null, null, null
        );
        final JsonObject item = new RepoListing(List.of(legacy), name -> true)
            .items(new RepoListing.Params(null, null, null, null, null)).get(0);
        MatcherAssert.assertThat("type", item.getString("type"), new IsEqual<>("rpm"));
        MatcherAssert.assertThat("mode", item.getString("mode"), new IsEqual<>("hosted"));
    }

    private static RepoSummary row(final String name, final String type, final String json,
        final Instant updated) {
        return new RepoSummary(
            name, type, Json.createReader(new StringReader(json)).readObject(),
            updated, updated == null ? null : "ayd", "bootstrap"
        );
    }

    private static List<RepoSummary> fixture() {
        return List.of(
            row(
                "maven-central", "maven-proxy",
                "{\"type\":\"maven-proxy\",\"storage\":{\"type\":\"fs\",\"path\":\"/d\"}}",
                Instant.parse("2026-10-01T00:00:00Z")
            ),
            row(
                "npm-local", "npm",
                "{\"type\":\"npm\",\"storage\":\"s3-main\",\"anonymous_read\":true,\"immutable\":false}",
                Instant.parse("2026-10-03T00:00:00Z")
            ),
            row("all-maven", "maven-group", "{\"type\":\"maven-group\",\"members\":[\"a\"]}", null)
        );
    }

    private static List<String> names(final List<JsonObject> items) {
        return items.stream().map(o -> o.getString("name")).collect(Collectors.toList());
    }
}
