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
package com.auto1.pantera.rpm;

import com.amihaiemil.eoyaml.Yaml;
import java.util.Optional;
import org.cactoos.list.ListOf;
import org.hamcrest.Matcher;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.AllOf;
import org.junit.jupiter.api.Test;
import org.llorllale.cactoos.matchers.Satisfies;

/**
 * Test for {@link RepoConfig.FromYaml}.
 * @since 0.10
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class RepoConfigFromYamlTest {

    @Test
    void readsSettings() {
        final String name = "any";
        MatcherAssert.assertThat(
            new RepoConfig.FromYaml(
                Yaml.createYamlMappingBuilder().add("digest", "sha1")
                .add("naming-policy", "sha256").add("filelists", "false")
                .add("update", Yaml.createYamlMappingBuilder().add("on", "upload").build()).build(),
                name
            ),
            new AllOf<>(
                new ListOf<Matcher<? super RepoConfig>>(
                    new Satisfies<>(cfg -> cfg.digest() == Digest.SHA1),
                    new Satisfies<>(cfg -> cfg.naming() == StandardNamingPolicy.SHA256),
                    new Satisfies<>(fromYaml -> !fromYaml.filelists()),
                    new Satisfies<>(cfg -> cfg.mode() == RepoConfig.UpdateMode.UPLOAD),
                    new Satisfies<>(cfg -> !cfg.cron().isPresent()),
                    new Satisfies<>(cfg -> name.equals(cfg.name()))
                )
            )
        );
    }

    @Test
    void readsSettingsWithCron() {
        final String cron = "0 * * * *";
        final String name = "repo1";
        MatcherAssert.assertThat(
            new RepoConfig.FromYaml(
                Yaml.createYamlMappingBuilder()
                    .add(
                        "update",
                        Yaml.createYamlMappingBuilder().add(
                            "on",
                            Yaml.createYamlMappingBuilder().add("cron", cron).build()
                        ).build()
                    ).build(),
                name
            ),
            new AllOf<>(
                new ListOf<Matcher<? super RepoConfig>>(
                    new Satisfies<>(cfg -> cfg.mode() == RepoConfig.UpdateMode.CRON),
                    new Satisfies<>(cfg -> cfg.cron().get().equals(cron)),
                    new Satisfies<>(cfg -> name.equals(cfg.name()))
                )
            )
        );
    }

    @Test
    void returnsDefaults() {
        final String name = "test";
        MatcherAssert.assertThat(
            new RepoConfig.FromYaml(Optional.empty(), name),
            new AllOf<>(
                new ListOf<Matcher<? super RepoConfig>>(
                    new Satisfies<>(cfg -> cfg.digest() == Digest.SHA256),
                    new Satisfies<>(cfg -> cfg.naming() == StandardNamingPolicy.SHA256),
                    new Satisfies<>(RepoConfig::filelists),
                    new Satisfies<>(cfg -> cfg.mode() == RepoConfig.UpdateMode.UPLOAD),
                    new Satisfies<>(cfg -> !cfg.cron().isPresent()),
                    new Satisfies<>(cfg -> cfg.name().equals(name))
                )
            )
        );
    }
}
