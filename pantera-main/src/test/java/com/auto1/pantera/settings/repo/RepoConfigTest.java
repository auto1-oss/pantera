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

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.cache.StoragesCache;
import com.auto1.pantera.http.client.RemoteConfig;
import com.auto1.pantera.settings.StorageByAlias;
import com.auto1.pantera.test.TestStoragesCache;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Test for {@link RepoConfig}.
 */
public final class RepoConfigTest {

    private StoragesCache cache;

    @BeforeEach
    public void setUp() {
        this.cache = new TestStoragesCache();
    }

    @Test
    public void readsCustom() throws Exception {
        final YamlMapping yaml = readFull().settings().orElseThrow();
        Assertions.assertEquals("custom-value", yaml.string("custom-property"));
    }

    @Test
    public void failsToReadCustom() throws Exception {
        Assertions.assertTrue(readMin().settings().isEmpty());
    }

    @Test
    public void readContentLengthMax() throws Exception {
        Assertions.assertEquals(Optional.of(123L), readFull().contentLengthMax());
    }

    @Test
    void remotesPriority() throws Exception {
        List<RemoteConfig> remotes = readFull().remotes();
        Assertions.assertEquals(4, remotes.size());
        Assertions.assertEquals(new RemoteConfig(URI.create("host4.com"), 200, null, null), remotes.getFirst());
        Assertions.assertEquals(new RemoteConfig(URI.create("host1.com"), 100, null, null), remotes.get(1));
        Assertions.assertEquals(new RemoteConfig(URI.create("host2.com"), 0, "test_user", "12345"), remotes.get(2));
        Assertions.assertEquals(new RemoteConfig(URI.create("host3.com"), -10, null, null), remotes.get(3));
    }

    @Test
    public void readEmptyContentLengthMax() throws Exception {
        Assertions.assertTrue(readMin().contentLengthMax().isEmpty());
    }

    @Test
    public void readsPortWhenSpecified() throws Exception {
        Assertions.assertEquals(OptionalInt.of(1234), readFull().port());
    }

    @Test
    public void readsEmptyPortWhenNotSpecified() throws Exception {
        Assertions.assertEquals(OptionalInt.empty(), readMin().port());
    }

    @Test
    public void readsRepositoryTypeRepoPart() throws Exception {
        Assertions.assertEquals("maven", readMin().type());
    }

    @Test
    public void throwExceptionWhenPathNotSpecified() {
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> repoCustom().path()
        );
    }

    @Test
    public void getPathPart() throws Exception {
        Assertions.assertEquals("mvn", readFull().path());
    }

    @Test
    public void getUrlWhenUrlIsCorrect() {
        final String target = "http://host:8080/correct";
        Assertions.assertEquals(target, repoCustom(target).url().toString());
    }

    @Test
    public void throwExceptionWhenUrlIsMalformed() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> repoCustom("host:8080/without/scheme").url()
        );
    }

    @Test
    public void throwsExceptionWhenStorageWithDefaultAliasesNotConfigured() {
        Assertions.assertEquals("Storage is not configured",
            Assertions.assertThrows(
                IllegalStateException.class,
                () -> repoCustom().storage()
            ).getMessage());
    }

    @Test
    public void throwsExceptionForInvalidStorageConfig() {
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> RepoConfig.from(
                Yaml.createYamlMappingBuilder().add(
                    "repo", Yaml.createYamlMappingBuilder()
                        .add(
                            "storage", Yaml.createYamlSequenceBuilder()
                                .add("wrong because sequence").build()
                        ).build()
                ).build(),
                new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
                new Key.From("key"), cache, false
            ).storage()
        );
    }

    @Test
    public void readsCooldownDuration() throws Exception {
        Assertions.assertEquals(
            Optional.of(Duration.ofDays(30)),
            readFromResource("repo-cooldown-config.yml").cooldownDuration()
        );
    }

    @Test
    public void cooldownDurationEmptyWhenNotConfigured() throws Exception {
        Assertions.assertEquals(Optional.empty(), readMin().cooldownDuration());
    }

    @Test
    public void cooldownDurationEmptyWhenFullConfigHasNoCooldown() throws Exception {
        Assertions.assertEquals(Optional.empty(), readFull().cooldownDuration());
    }

    @Test
    public void prefetchDefaultsFalseWhenAbsentForProxy() throws Exception {
        // repo-cooldown-config.yml uses pypi-proxy with no settings.prefetch.
        // Default flipped 2026-05-13 (analysis/03-findings.md #1): opt-in
        // everywhere. The old "proxy default to true" was the regression
        // vehicle for the Maven Central 429 storms.
        Assertions.assertFalse(
            readFromResource("repo-cooldown-config.yml").prefetchEnabled(),
            "any repo without settings.prefetch must default to disabled"
        );
    }

    @Test
    public void prefetchDefaultsFalseForNonProxyTypes() throws Exception {
        // repo-min-config.yml uses 'maven' (hosted).
        Assertions.assertFalse(
            readMin().prefetchEnabled(),
            "hosted (non-proxy) repo without settings.prefetch should default to disabled"
        );
    }

    @Test
    public void prefetchRespectsExplicitFalse() {
        final RepoConfig cfg = RepoConfig.from(
            Yaml.createYamlMappingBuilder().add(
                "repo", Yaml.createYamlMappingBuilder()
                    .add("type", "maven-proxy")
                    .add("url", "http://upstream.example.com")
                    .add(
                        "settings",
                        Yaml.createYamlMappingBuilder().add("prefetch", "false").build()
                    ).build()
            ).build(),
            new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
            new Key.From("explicit-false.yml"), cache, false
        );
        Assertions.assertFalse(
            cfg.prefetchEnabled(),
            "settings.prefetch=false stays disabled"
        );
    }

    @Test
    public void prefetchRespectsExplicitTrue() {
        final RepoConfig cfg = RepoConfig.from(
            Yaml.createYamlMappingBuilder().add(
                "repo", Yaml.createYamlMappingBuilder()
                    .add("type", "maven")
                    .add(
                        "settings",
                        Yaml.createYamlMappingBuilder().add("prefetch", "true").build()
                    ).build()
            ).build(),
            new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
            new Key.From("explicit-true.yml"), cache, false
        );
        Assertions.assertTrue(
            cfg.prefetchEnabled(),
            "settings.prefetch=true must override the non-proxy default"
        );
    }

    private RepoConfig readFull() throws Exception {
        return readFromResource("repo-full-config.yml");
    }

    private RepoConfig readMin() throws Exception {
        return readFromResource("repo-min-config.yml");
    }

    private RepoConfig repoCustom() {
        return repoCustom("http://host:8080/correct");
    }

    private RepoConfig repoCustom(final String value) {
        return RepoConfig.from(
            Yaml.createYamlMappingBuilder().add(
                "repo", Yaml.createYamlMappingBuilder()
                    .add("type", "maven")
                    .add("url", value)
                    .build()
            ).build(),
            new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
            new Key.From("repo-custom.yml"), cache, false
        );
    }

    private RepoConfig readFromResource(final String name) throws IOException {
        return RepoConfig.from(
            Yaml.createYamlInput(
                new TestResource(name).asInputStream()
            ).readYamlMapping(),
            new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
            new Key.From(name), cache, false
        );
    }
}
