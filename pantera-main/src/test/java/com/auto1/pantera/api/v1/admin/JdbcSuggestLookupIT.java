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

import com.auto1.pantera.db.DbManager;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SQL of {@link JdbcSuggestLookup} against a real, migrated PostgreSQL.
 *
 * @since 2.2.9
 */
@Testcontainers
final class JdbcSuggestLookupIT {

    /**
     * PostgreSQL.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgreSQLTestConfig.createContainer();

    /**
     * Pool.
     */
    private static HikariDataSource source;

    @BeforeAll
    static void pool() {
        final HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
        cfg.setUsername(POSTGRES.getUsername());
        cfg.setPassword(POSTGRES.getPassword());
        cfg.setMaximumPoolSize(4);
        JdbcSuggestLookupIT.source = new HikariDataSource(cfg);
        DbManager.migrate(JdbcSuggestLookupIT.source);
    }

    @AfterAll
    static void close() {
        JdbcSuggestLookupIT.source.close();
    }

    @BeforeEach
    void seed() throws Exception {
        try (Connection conn = JdbcSuggestLookupIT.source.getConnection();
            Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM artifacts");
            stmt.executeUpdate("DELETE FROM artifact_cooldowns");
            stmt.executeUpdate("DELETE FROM artifact_cooldowns_history");
        }
        this.artifact("maven-proxy", "maven_proxy", "com.fasterxml.jackson.core.jackson-databind",
            "2.17.0", "com/fasterxml/jackson/core/jackson-databind/2.17.0");
        this.artifact("maven-proxy", "maven_proxy", "com.fasterxml.jackson.core.jackson-databind",
            "2.18.0", "com/fasterxml/jackson/core/jackson-databind/2.18.0");
        this.artifact("maven-proxy", "maven_proxy", "com.theokanning.openai-gpt3-java.api",
            "0.18.2", "com/theokanning/openai-gpt3-java/api/0.18.2");
        this.artifact("npm-proxy", "npm_proxy", "@types/node", "20.0.0", null);
        this.artifact("npm-proxy", "npm_proxy", "okhttp5_shim", "1.0.0", null);
        this.artifact("npm-proxy", "npm_proxy", "okhttp5-shim", "1.0.0", null);
        this.artifact("pypi-proxy", "pypi_proxy", "OpenAI_Client", "1.0.0", null);
        this.cooldown("npm-group", "npm_group", "openai");
        this.history("maven-proxy", "maven_proxy", "com.theokanning.openai-gpt3-java.api");
    }

    @Test
    void findsOneRowPerRepositoryAndNameAcrossSources() {
        final List<SuggestRow> rows = new JdbcSuggestLookup(JdbcSuggestLookupIT.source)
            .rows(List.of(), new SuggestQuery("openai"), 50);
        MatcherAssert.assertThat(
            rows.stream().map(row -> row.source() + ":" + row.repoName() + ":" + row.name())
                .sorted().collect(Collectors.toList()),
            new IsEqual<>(List.of(
                "cooldown:maven_proxy:com.theokanning.openai-gpt3-java.api",
                "cooldown:npm_group:openai",
                "index:maven_proxy:com.theokanning.openai-gpt3-java.api",
                "index:pypi_proxy:OpenAI_Client"
            ))
        );
    }

    @Test
    void treatsWildcardsLiterallyAndFiltersFamilies() {
        MatcherAssert.assertThat(
            "a percent sign is not a wildcard",
            new JdbcSuggestLookup(JdbcSuggestLookupIT.source)
                .rows(List.of("npm"), new SuggestQuery("http5%"), 50).size(),
            new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "an underscore is matched literally once the names are re-checked",
            new PackageSuggester(new JdbcSuggestLookup(JdbcSuggestLookupIT.source))
                .suggest("npm", "okhttp5_", 20).stream()
                .map(obj -> ((JsonObject) obj).getString("package"))
                .collect(Collectors.toList()),
            new IsEqual<>(List.of("okhttp5_shim"))
        );
        MatcherAssert.assertThat(
            "the family filter keeps npm types only",
            new JdbcSuggestLookup(JdbcSuggestLookupIT.source)
                .rows(List.of("npm"), new SuggestQuery("openai"), 50).stream()
                .map(SuggestRow::name).collect(Collectors.toList()),
            new IsEqual<>(List.of("openai"))
        );
    }

    @Test
    void suggestsInspectorFormsEndToEnd() {
        final JsonArray found = new PackageSuggester(new JdbcSuggestLookup(JdbcSuggestLookupIT.source))
            .suggest("maven", "core:jackson", 20);
        MatcherAssert.assertThat(
            "the colon form matches the dotted stored name",
            found.stream().map(obj -> ((JsonObject) obj).getString("package"))
                .collect(Collectors.toList()),
            new IsEqual<>(List.of("com.fasterxml.jackson.core:jackson-databind"))
        );
        MatcherAssert.assertThat(
            "multi-word, npm scope",
            new PackageSuggester(new JdbcSuggestLookup(JdbcSuggestLookupIT.source))
                .suggest(null, "@types node", 20).getJsonObject(0).getString("package"),
            new IsEqual<>("@types/node")
        );
    }

    @Test
    void resolvesMavenPathsOfDottedNames() {
        MatcherAssert.assertThat(
            new JdbcSuggestLookup(JdbcSuggestLookupIT.source).mavenPaths(
                List.of("com.theokanning.openai-gpt3-java.api", "unknown.name")
            ),
            new IsEqual<>(Map.of(
                "com.theokanning.openai-gpt3-java.api",
                "com/theokanning/openai-gpt3-java/api/0.18.2"
            ))
        );
    }

    /**
     * Insert an index row.
     *
     * @param type Repo type
     * @param repo Repo name
     * @param name Name
     * @param version Version
     * @param path Path prefix
     * @throws Exception On failure
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private void artifact(
        final String type, final String repo, final String name,
        final String version, final String path
    ) throws Exception {
        try (Connection conn = JdbcSuggestLookupIT.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO artifacts (repo_type, repo_name, name, version, size,"
                    + " created_date, owner, path_prefix) VALUES (?, ?, ?, ?, 1, 1, 'alice', ?)"
            )) {
            stmt.setString(1, type);
            stmt.setString(2, repo);
            stmt.setString(3, name);
            stmt.setString(4, version);
            stmt.setString(5, path);
            stmt.executeUpdate();
        }
    }

    /**
     * Insert a live cooldown row.
     *
     * @param type Repo type
     * @param repo Repo name
     * @param name Artifact
     * @throws Exception On failure
     */
    private void cooldown(final String type, final String repo, final String name)
        throws Exception {
        try (Connection conn = JdbcSuggestLookupIT.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO artifact_cooldowns (repo_type, repo_name, artifact, version,"
                    + " reason, status, blocked_by, blocked_at, blocked_until)"
                    + " VALUES (?, ?, ?, '1.0.0', 'FRESH_RELEASE', 'ACTIVE', 'system', 1, 2)"
            )) {
            stmt.setString(1, type);
            stmt.setString(2, repo);
            stmt.setString(3, name);
            stmt.executeUpdate();
        }
    }

    /**
     * Insert an archived cooldown row.
     *
     * @param type Repo type
     * @param repo Repo name
     * @param name Artifact
     * @throws Exception On failure
     */
    private void history(final String type, final String repo, final String name)
        throws Exception {
        try (Connection conn = JdbcSuggestLookupIT.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO artifact_cooldowns_history (original_id, repo_type, repo_name,"
                    + " artifact, version, reason, blocked_by, blocked_at, blocked_until,"
                    + " archived_at, archive_reason, archived_by)"
                    + " VALUES (1, ?, ?, ?, '0.18.2', 'FRESH_RELEASE', 'system', 1, 2, 3,"
                    + " 'EXPIRED', 'system')"
            )) {
            stmt.setString(1, type);
            stmt.setString(2, repo);
            stmt.setString(3, name);
            stmt.executeUpdate();
        }
    }
}
