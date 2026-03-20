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
package com.auto1.pantera.settings;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.scheduling.QuartzService;
import com.auto1.pantera.security.policy.CachedYamlPolicy;
import com.auto1.pantera.security.policy.Policy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsInstanceOf;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link YamlSettings}.
 *
 * @since 0.1
 */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class YamlSettingsTest {

    /**
     * Test directory.
     */
    @TempDir
    Path temp;

    @Test
    void shouldBuildFileStorageFromSettings() throws Exception {
        final YamlSettings settings = new YamlSettings(
            this.config("some/path"), this.temp, new QuartzService()
        );
        MatcherAssert.assertThat(
            settings.configStorage(),
            Matchers.notNullValue()
        );
    }

    @Test
    void returnsRepoConfigs(@TempDir final Path tmp) {
        MatcherAssert.assertThat(
            new YamlSettings(
                this.config(tmp.toString()), tmp, new QuartzService()
            ).repoConfigsStorage(),
            new IsInstanceOf(SubStorage.class)
        );
    }

    @ParameterizedTest
    @MethodSource("badYamls")
    void shouldFailProvideStorageFromBadYaml(final String yaml) throws IOException {
        final YamlSettings settings = new YamlSettings(
            Yaml.createYamlInput(yaml).readYamlMapping(), this.temp, new QuartzService()
        );
        Assertions.assertThrows(RuntimeException.class, settings::configStorage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "meta:\n"})
    void throwsErrorIfMetaSectionIsAbsentOrEmpty(final String yaml) {
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> new YamlSettings(
                Yaml.createYamlInput(yaml).readYamlMapping(), this.temp, new QuartzService()
            ).meta()
        );
    }

    @Test
    void initializesEnvAuth() throws IOException {
        final YamlSettings authz = new YamlSettings(
            Yaml.createYamlInput(this.envCreds()).readYamlMapping(), this.temp, new QuartzService()
        );
        MatcherAssert.assertThat(
            "Env credentials are initialized",
            authz.authz().authentication().toString(),
            new StringContains("AuthFromEnv")
        );
        MatcherAssert.assertThat(
            "Policy is free",
            authz.authz().policy(),
            new IsInstanceOf(Policy.FREE.getClass())
        );
        MatcherAssert.assertThat(
            "Policy storage is absent",
            authz.authz().policyStorage().isEmpty()
        );
    }

    @Test
    void initializesGithubAuth() throws IOException {
        final YamlSettings authz = new YamlSettings(
            Yaml.createYamlInput(this.githubCreds()).readYamlMapping(), this.temp,
            new QuartzService()
        );
        MatcherAssert.assertThat(
            "Github auth created",
            authz.authz().authentication().toString(),
            new StringContains("GithubAuth")
        );
        MatcherAssert.assertThat(
            "Policy is free",
            authz.authz().policy(),
            new IsInstanceOf(Policy.FREE.getClass())
        );
        MatcherAssert.assertThat(
            "Policy storage is absent",
            authz.authz().policyStorage().isEmpty()
        );
    }

    @Test
    void initializesKeycloakAuth() throws IOException {
        final YamlSettings authz = new YamlSettings(
            Yaml.createYamlInput(this.keycloakCreds()).readYamlMapping(), this.temp,
            new QuartzService()
        );
        MatcherAssert.assertThat(
            "Keycloak storage created",
            authz.authz().authentication().toString(),
            new StringContains("AuthFromKeycloak")
        );
        MatcherAssert.assertThat(
            "Policy is free",
            authz.authz().policy(),
            new IsInstanceOf(Policy.FREE.getClass())
        );
        MatcherAssert.assertThat(
            "Policy storage is absent",
            authz.authz().policyStorage().isEmpty()
        );
    }

    @Test
    void initializesPanteraAuth() throws IOException {
        final YamlSettings authz = new YamlSettings(
            Yaml.createYamlInput(this.panteraCreds()).readYamlMapping(), this.temp,
            new QuartzService()
        );
        MatcherAssert.assertThat(
            "Auth from storage initiated",
            authz.authz().authentication().toString(),
            new StringContains("AuthFromStorage")
        );
        MatcherAssert.assertThat(
            "Policy is free",
            authz.authz().policy(),
            new IsInstanceOf(Policy.FREE.getClass())
        );
        MatcherAssert.assertThat(
            "Policy storage is present",
            authz.authz().policyStorage().isPresent()
        );
    }

    @Test
    void initializesPanteraAuthAndPolicy() throws IOException {
        final YamlSettings authz = new YamlSettings(
            Yaml.createYamlInput(this.panteraCredsWithPolicy()).readYamlMapping(), this.temp,
            new QuartzService()
        );
        MatcherAssert.assertThat(
            "Auth from storage initiated",
            authz.authz().authentication().toString(),
            new StringContains("AuthFromStorage")
        );
        MatcherAssert.assertThat(
            "CachedYamlPolicy created",
            authz.authz().policy(),
            new IsInstanceOf(CachedYamlPolicy.class)
        );
        MatcherAssert.assertThat(
            "Policy storage is present",
            authz.authz().policyStorage().isPresent()
        );
    }

    @Test
    void initializesAllAuths() throws IOException {
        final YamlSettings authz = new YamlSettings(
            Yaml.createYamlInput(this.panteraGithubKeycloakEnvCreds()).readYamlMapping(), this.temp,
            new QuartzService()
        );
        MatcherAssert.assertThat(
            "Auth from storage, github, env and keycloak initiated",
            authz.authz().authentication().toString(),
            Matchers.allOf(
                new StringContains("AuthFromStorage"),
                new StringContains("AuthFromKeycloak"),
                new StringContains("GithubAuth"),
                new StringContains("AuthFromEnv")
            )
        );
        MatcherAssert.assertThat(
            "Empty policy created",
            authz.authz().policy(),
            new IsInstanceOf(Policy.FREE.getClass())
        );
        MatcherAssert.assertThat(
            "Policy storage is present",
            authz.authz().policyStorage().isPresent()
        );
    }

    @Test
    void initializesAllAuthsAndPolicy() throws IOException {
        final YamlSettings settings = new YamlSettings(
            Yaml.createYamlInput(this.panteraGithubKeycloakEnvCredsAndPolicy()).readYamlMapping(),
            this.temp, new QuartzService()
        );
        MatcherAssert.assertThat(
            "Auth from storage, github, env and keycloak initiated",
            settings.authz().authentication().toString(),
            Matchers.allOf(
                new StringContains("AuthFromStorage"),
                new StringContains("AuthFromKeycloak"),
                new StringContains("GithubAuth"),
                new StringContains("AuthFromEnv")
            )
        );
        MatcherAssert.assertThat(
            "CachedYamlPolicy created",
            settings.authz().policy(),
            new IsInstanceOf(CachedYamlPolicy.class)
        );
        MatcherAssert.assertThat(
            "Policy storage is present",
            settings.authz().policyStorage().isPresent()
        );
    }

    private String envCreds() {
        return String.join(
            "\n",
            "meta:",
            "  credentials:",
            "    - type: env"
        );
    }

    private String githubCreds() {
        return String.join(
            "\n",
            "meta:",
            "  credentials:",
            "    - type: github"
        );
    }

    private String keycloakCreds() {
        return String.join(
            "\n",
            "meta:",
            "  credentials:",
            "    - type: keycloak",
            "      url: http://any",
            "      realm: any",
            "      client-id: any",
            "      client-password: abc123"
        );
    }

    private String panteraCreds() {
        return String.join(
            "\n",
            "meta:",
            "  credentials:",
            "    - type: local",
            "      storage:",
            "        type: fs",
            "        path: any"
        );
    }

    private String panteraCredsWithPolicy() {
        return String.join(
            "\n",
            "meta:",
            "  credentials:",
            "    - type: local",
            "  policy:",
            "    type: local",
            "    storage:",
            "      type: fs",
            "      path: /any/path"
        );
    }

    private String panteraGithubKeycloakEnvCreds() {
        return String.join(
            "\n",
            "meta:",
            "  credentials:",
            "    - type: github",
            "    - type: env",
            "    - type: keycloak",
            "      url: http://any",
            "      realm: any",
            "      client-id: any",
            "      client-password: abc123",
            "    - type: local",
            "      storage:",
            "        type: fs",
            "        path: any"
        );
    }

    private String panteraGithubKeycloakEnvCredsAndPolicy() {
        return String.join(
            "\n",
            "meta:",
            "  credentials:",
            "    - type: github",
            "    - type: env",
            "    - type: keycloak",
            "      url: http://any",
            "      realm: any",
            "      client-id: any",
            "      client-password: abc123",
            "    - type: local",
            "  policy:",
            "    type: local",
            "    storage:",
            "      type: fs",
            "      path: /any/path"
        );
    }

    @Test
    void closeIsIdempotent() throws Exception {
        final YamlSettings settings = new YamlSettings(
            this.config("some/path"), this.temp, new QuartzService()
        );
        settings.close();
        Assertions.assertDoesNotThrow(
            settings::close,
            "Calling close() a second time should not throw"
        );
    }

    @Test
    void closeWithNoDatabaseOrValkey() throws Exception {
        final YamlSettings settings = new YamlSettings(
            this.config("some/path"), this.temp, new QuartzService()
        );
        Assertions.assertDoesNotThrow(
            settings::close,
            "close() should complete without error when no database or Valkey is configured"
        );
    }

    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private static Stream<String> badYamls() {
        return Stream.of(
            "meta:\n  storage:\n",
            "meta:\n  storage:\n    type: unknown\n",
            "meta:\n  storage:\n    type: fs\n",
            "meta:\n  storage:\n    type: s3\n",
            String.join(
                "",
                "meta:\n",
                "  storage:\n",
                "    type: s3\n",
                "    bucket: my-bucket\n",
                "    region: my-region\n",
                "    endpoint: https://my-s3-provider.com\n",
                "    credentials:\n",
                "      type: unknown\n"
            )
        );
    }

    private YamlMapping config(final String stpath) {
        return Yaml.createYamlMappingBuilder()
            .add(
                "meta",
                Yaml.createYamlMappingBuilder()
                    .add(
                        "storage",
                        Yaml.createYamlMappingBuilder()
                            .add("type", "fs")
                            .add("path", stpath).build()
                    )
                    .add("repo_configs", "repos")
                    .build()
            ).build();
    }
}
