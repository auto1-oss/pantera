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
package com.auto1.pantera.settings.repo;

import com.auto1.pantera.api.ManageRepoSettings;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNull;
import org.junit.jupiter.api.Test;

/**
 * Tests for the default {@link CrudRepoSettings#summaries()} derivation
 * used by the YAML-backed settings.
 */
final class RepoSummaryDefaultTest {

    @Test
    void derivesSummariesFromYamlFiles() {
        final BlockingStorage blocking = new BlockingStorage(new InMemoryStorage());
        blocking.save(
            new Key.From("npm-proxy.yaml"),
            "repo:\n  type: npm-proxy\n  storage: s3-main\n  anonymous_read: true\n"
                .getBytes(StandardCharsets.UTF_8)
        );
        blocking.save(
            new Key.From("files.yml"),
            "repo:\n  type: file\n  storage:\n    type: fs\n    path: /var/pantera/data\n"
                .getBytes(StandardCharsets.UTF_8)
        );
        final Collection<RepoSummary> rows = new ManageRepoSettings(blocking).summaries();
        final Map<String, RepoSummary> named = rows.stream()
            .collect(Collectors.toMap(RepoSummary::name, Function.identity()));
        MatcherAssert.assertThat("two rows", rows.size(), new IsEqual<>(2));
        MatcherAssert.assertThat(
            "type from repo section", named.get("npm-proxy").type(), new IsEqual<>("npm-proxy")
        );
        MatcherAssert.assertThat(
            "repo object unwrapped",
            named.get("npm-proxy").repo().getString("storage"), new IsEqual<>("s3-main")
        );
        MatcherAssert.assertThat(
            "no audit columns without a DB", named.get("files").updatedAt(), new IsNull<>()
        );
    }

    @Test
    void unreadableConfigStillListsAsUnknown() {
        final BlockingStorage blocking = new BlockingStorage(new InMemoryStorage());
        blocking.save(
            new Key.From("broken.yaml"), "repo: [not: a: mapping".getBytes(StandardCharsets.UTF_8)
        );
        final Collection<RepoSummary> rows = new ManageRepoSettings(blocking).summaries();
        MatcherAssert.assertThat("one row", rows.size(), new IsEqual<>(1));
        MatcherAssert.assertThat(
            "type unknown", rows.iterator().next().type(), new IsEqual<>("unknown")
        );
    }

    @Test
    void summaryFactoryPrefersTypeColumnAndUnwrapsRepo() {
        final RepoSummary row = new RepoSummaries().from(
            "x", "maven-proxy",
            javax.json.Json.createObjectBuilder().add(
                "repo", javax.json.Json.createObjectBuilder().add("type", "stale")
            ).build(),
            java.time.Instant.parse("2026-10-01T00:00:00Z"), "ayd", "boot"
        );
        MatcherAssert.assertThat("column wins", row.type(), new IsEqual<>("maven-proxy"));
        MatcherAssert.assertThat(
            "repo unwrapped", row.repo().getString("type"), new IsEqual<>("stale")
        );
        MatcherAssert.assertThat("updater", row.updatedBy(), new IsEqual<>("ayd"));
    }
}
