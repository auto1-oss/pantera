/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.backfill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for the backfill CLI pipeline.
 *
 * <p>Dry-run tests (always run) exercise the full pipeline
 * {@code BackfillCli -> ScannerFactory -> Scanner -> BatchInserter(dry-run)}
 * for every supported scanner type with minimal but valid sample data.</p>
 *
 * <p>PostgreSQL tests (gated behind the {@code BACKFILL_IT_DB_URL}
 * environment variable) verify actual database inserts and
 * UPSERT idempotency against a real PostgreSQL instance.</p>
 *
 * @since 1.20.13
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
final class BackfillIntegrationTest {

    // ---------------------------------------------------------------
    // Dry-run tests (always run)
    // ---------------------------------------------------------------

    /**
     * Maven scanner dry-run: creates a minimal maven-metadata.xml with
     * one version directory containing a JAR and verifies exit code 0.
     *
     * @param tmp Temporary directory created by JUnit
     * @throws IOException If temp file creation fails
     */
    @Test
    @Order(1)
    void dryRunMavenScanner(@TempDir final Path tmp) throws IOException {
        final Path artifact = tmp.resolve("com/example/mylib");
        Files.createDirectories(artifact);
        Files.writeString(
            artifact.resolve("maven-metadata.xml"),
            String.join(
                "\n",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<metadata>",
                "  <groupId>com.example</groupId>",
                "  <artifactId>mylib</artifactId>",
                "  <versioning>",
                "    <versions>",
                "      <version>1.0.0</version>",
                "    </versions>",
                "  </versioning>",
                "</metadata>"
            ),
            StandardCharsets.UTF_8
        );
        final Path ver = artifact.resolve("1.0.0");
        Files.createDirectories(ver);
        Files.write(ver.resolve("mylib-1.0.0.jar"), new byte[64]);
        final int code = BackfillCli.run(
            "--type", "maven",
            "--path", tmp.toString(),
            "--repo-name", "it-maven",
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "Maven dry-run should succeed",
            code,
            Matchers.is(0)
        );
    }

    /**
     * Docker scanner dry-run: creates a minimal Docker registry layout
     * with one image, one tag, and a manifest blob.
     *
     * @param tmp Temporary directory created by JUnit
     * @throws IOException If temp file creation fails
     */
    @Test
    @Order(2)
    void dryRunDockerScanner(@TempDir final Path tmp) throws IOException {
        final String digest = "sha256:aabbccdd11223344";
        final Path linkDir = tmp
            .resolve("repositories")
            .resolve("alpine")
            .resolve("_manifests")
            .resolve("tags")
            .resolve("3.18")
            .resolve("current");
        Files.createDirectories(linkDir);
        Files.writeString(
            linkDir.resolve("link"), digest, StandardCharsets.UTF_8
        );
        final String hex = digest.split(":", 2)[1];
        final Path blobDir = tmp.resolve("blobs")
            .resolve("sha256")
            .resolve(hex.substring(0, 2))
            .resolve(hex);
        Files.createDirectories(blobDir);
        Files.writeString(
            blobDir.resolve("data"),
            String.join(
                "\n",
                "{",
                "  \"schemaVersion\": 2,",
                "  \"config\": { \"size\": 100, \"digest\": \"sha256:cfg\" },",
                "  \"layers\": [",
                "    { \"size\": 500, \"digest\": \"sha256:l1\" }",
                "  ]",
                "}"
            ),
            StandardCharsets.UTF_8
        );
        final int code = BackfillCli.run(
            "--type", "docker",
            "--path", tmp.toString(),
            "--repo-name", "it-docker",
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "Docker dry-run should succeed",
            code,
            Matchers.is(0)
        );
    }

    /**
     * NPM scanner dry-run: creates a meta.json with one scoped package
     * and one version entry.
     *
     * @param tmp Temporary directory created by JUnit
     * @throws IOException If temp file creation fails
     */
    @Test
    @Order(3)
    void dryRunNpmScanner(@TempDir final Path tmp) throws IOException {
        final Path pkgDir = tmp.resolve("@scope/widget");
        Files.createDirectories(pkgDir);
        Files.writeString(
            pkgDir.resolve("meta.json"),
            String.join(
                "\n",
                "{",
                "  \"name\": \"@scope/widget\",",
                "  \"versions\": {",
                "    \"2.0.0\": {",
                "      \"name\": \"@scope/widget\",",
                "      \"version\": \"2.0.0\",",
                "      \"dist\": {",
                "        \"tarball\": \"/@scope/widget/-/"
                    + "@scope/widget-2.0.0.tgz\"",
                "      }",
                "    }",
                "  }",
                "}"
            ),
            StandardCharsets.UTF_8
        );
        final int code = BackfillCli.run(
            "--type", "npm",
            "--path", tmp.toString(),
            "--repo-name", "it-npm",
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "NPM dry-run should succeed",
            code,
            Matchers.is(0)
        );
    }

    /**
     * PyPI scanner dry-run: creates a wheel file in a package directory.
     *
     * @param tmp Temporary directory created by JUnit
     * @throws IOException If temp file creation fails
     */
    @Test
    @Order(4)
    void dryRunPypiScanner(@TempDir final Path tmp) throws IOException {
        final Path pkgDir = tmp.resolve("requests");
        Files.createDirectories(pkgDir);
        Files.write(
            pkgDir.resolve("requests-2.31.0-py3-none-any.whl"),
            new byte[80]
        );
        final int code = BackfillCli.run(
            "--type", "pypi",
            "--path", tmp.toString(),
            "--repo-name", "it-pypi",
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "PyPI dry-run should succeed",
            code,
            Matchers.is(0)
        );
    }

    /**
     * Go scanner dry-run: creates a module {@code @v} directory with
     * a version list file and a .info JSON file.
     *
     * @param tmp Temporary directory created by JUnit
     * @throws IOException If temp file creation fails
     */
    @Test
    @Order(5)
    void dryRunGoScanner(@TempDir final Path tmp) throws IOException {
        final Path atv = tmp.resolve("example.com/mod/@v");
        Files.createDirectories(atv);
        Files.writeString(
            atv.resolve("list"),
            "v1.0.0\n",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            atv.resolve("v1.0.0.info"),
            "{\"Version\":\"v1.0.0\","
                + "\"Time\":\"2024-01-01T00:00:00Z\"}",
            StandardCharsets.UTF_8
        );
        Files.write(atv.resolve("v1.0.0.zip"), new byte[128]);
        final int code = BackfillCli.run(
            "--type", "go",
            "--path", tmp.toString(),
            "--repo-name", "it-go",
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "Go dry-run should succeed",
            code,
            Matchers.is(0)
        );
    }

    /**
     * Helm scanner dry-run: creates an index.yaml with one chart entry
     * and a corresponding .tgz file.
     *
     * @param tmp Temporary directory created by JUnit
     * @throws IOException If temp file creation fails
     */
    @Test
    @Order(6)
    void dryRunHelmScanner(@TempDir final Path tmp) throws IOException {
        Files.writeString(
            tmp.resolve("index.yaml"),
            String.join(
                "\n",
                "apiVersion: v1",
                "entries:",
                "  mychart:",
                "    - name: mychart",
                "      version: 0.1.0",
                "      urls:",
                "        - mychart-0.1.0.tgz",
                "      created: '2024-06-01T00:00:00+00:00'"
            ),
            StandardCharsets.UTF_8
        );
        Files.write(tmp.resolve("mychart-0.1.0.tgz"), new byte[256]);
        final int code = BackfillCli.run(
            "--type", "helm",
            "--path", tmp.toString(),
            "--repo-name", "it-helm",
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "Helm dry-run should succeed",
            code,
            Matchers.is(0)
        );
    }

    /**
     * Composer scanner dry-run: creates a p2 layout with one package
     * JSON file containing one vendor/package with one version.
     *
     * @param tmp Temporary directory created by JUnit
     * @throws IOException If temp file creation fails
     */
    @Test
    @Order(7)
    void dryRunComposerScanner(@TempDir final Path tmp) throws IOException {
        final Path vendorDir = tmp.resolve("p2").resolve("vendor");
        Files.createDirectories(vendorDir);
        Files.writeString(
            vendorDir.resolve("lib.json"),
            String.join(
                "\n",
                "{",
                "  \"packages\": {",
                "    \"vendor/lib\": {",
                "      \"1.0.0\": {",
                "        \"name\": \"vendor/lib\",",
                "        \"version\": \"1.0.0\",",
                "        \"dist\": {",
                "          \"url\": \"https://example.com/lib.zip\",",
                "          \"type\": \"zip\"",
                "        }",
                "      }",
                "    }",
                "  }",
                "}"
            ),
            StandardCharsets.UTF_8
        );
        final int code = BackfillCli.run(
            "--type", "composer",
            "--path", tmp.toString(),
            "--repo-name", "it-composer",
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "Composer dry-run should succeed",
            code,
            Matchers.is(0)
        );
    }

    /**
     * File scanner dry-run: creates a couple of plain files and one
     * hidden file that should be skipped.
     *
     * @param tmp Temporary directory created by JUnit
     * @throws IOException If temp file creation fails
     */
    @Test
    @Order(8)
    void dryRunFileScanner(@TempDir final Path tmp) throws IOException {
        Files.createFile(tmp.resolve("readme.txt"));
        Files.write(tmp.resolve("data.bin"), new byte[32]);
        Files.createFile(tmp.resolve(".hidden"));
        final int code = BackfillCli.run(
            "--type", "file",
            "--path", tmp.toString(),
            "--repo-name", "it-file",
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "File dry-run should succeed",
            code,
            Matchers.is(0)
        );
    }

    // ---------------------------------------------------------------
    // PostgreSQL tests (gated behind BACKFILL_IT_DB_URL)
    // ---------------------------------------------------------------

    /**
     * Insert records into a real PostgreSQL instance via the CLI pipeline
     * and verify the row count matches the expected number.
     *
     * <p>Requires the following environment variables:</p>
     * <ul>
     *   <li>{@code BACKFILL_IT_DB_URL} - JDBC URL, e.g.
     *       {@code jdbc:postgresql://localhost:5432/artipie}</li>
     *   <li>{@code BACKFILL_IT_DB_USER} - (optional, default: artipie)</li>
     *   <li>{@code BACKFILL_IT_DB_PASSWORD} - (optional, default: artipie)</li>
     * </ul>
     *
     * @param tmp Temporary directory created by JUnit
     * @throws Exception If I/O or SQL operations fail
     */
    @Test
    @Order(10)
    @EnabledIfEnvironmentVariable(named = "BACKFILL_IT_DB_URL", matches = ".+")
    void insertsRecordsIntoPostgres(@TempDir final Path tmp) throws Exception {
        final String dbUrl = System.getenv("BACKFILL_IT_DB_URL");
        final String dbUser = System.getenv().getOrDefault(
            "BACKFILL_IT_DB_USER", "artipie"
        );
        final String dbPassword = System.getenv().getOrDefault(
            "BACKFILL_IT_DB_PASSWORD", "artipie"
        );
        final String repoName = "it-pg-maven-" + System.nanoTime();
        final Path artifact = tmp.resolve("org/test/pglib");
        Files.createDirectories(artifact);
        Files.writeString(
            artifact.resolve("maven-metadata.xml"),
            String.join(
                "\n",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<metadata>",
                "  <groupId>org.test</groupId>",
                "  <artifactId>pglib</artifactId>",
                "  <versioning>",
                "    <versions>",
                "      <version>1.0.0</version>",
                "      <version>2.0.0</version>",
                "    </versions>",
                "  </versioning>",
                "</metadata>"
            ),
            StandardCharsets.UTF_8
        );
        final Path ver1 = artifact.resolve("1.0.0");
        Files.createDirectories(ver1);
        Files.write(ver1.resolve("pglib-1.0.0.jar"), new byte[100]);
        final Path ver2 = artifact.resolve("2.0.0");
        Files.createDirectories(ver2);
        Files.write(ver2.resolve("pglib-2.0.0.jar"), new byte[200]);
        final int code = BackfillCli.run(
            "--type", "maven",
            "--path", tmp.toString(),
            "--repo-name", repoName,
            "--db-url", dbUrl,
            "--db-user", dbUser,
            "--db-password", dbPassword,
            "--batch-size", "10"
        );
        MatcherAssert.assertThat(
            "CLI should succeed inserting into PostgreSQL",
            code,
            Matchers.is(0)
        );
        final long count;
        try (Connection conn =
            DriverManager.getConnection(dbUrl, dbUser, dbPassword);
            Statement stmt = conn.createStatement();
            ResultSet rset = stmt.executeQuery(
                "SELECT count(*) FROM artifacts WHERE repo_name = '"
                    + repoName + "'"
            )) {
            rset.next();
            count = rset.getLong(1);
        }
        MatcherAssert.assertThat(
            "Should have inserted exactly 2 records",
            count,
            Matchers.is(2L)
        );
    }

    /**
     * Run the same backfill again and verify the UPSERT does not
     * duplicate rows (idempotency check).
     *
     * @param tmp Temporary directory created by JUnit
     * @throws Exception If I/O or SQL operations fail
     */
    @Test
    @Order(11)
    @EnabledIfEnvironmentVariable(named = "BACKFILL_IT_DB_URL", matches = ".+")
    void upsertIsIdempotent(@TempDir final Path tmp) throws Exception {
        final String dbUrl = System.getenv("BACKFILL_IT_DB_URL");
        final String dbUser = System.getenv().getOrDefault(
            "BACKFILL_IT_DB_USER", "artipie"
        );
        final String dbPassword = System.getenv().getOrDefault(
            "BACKFILL_IT_DB_PASSWORD", "artipie"
        );
        final String repoName = "it-pg-idempotent-" + System.nanoTime();
        final Path artifact = tmp.resolve("org/test/idem");
        Files.createDirectories(artifact);
        Files.writeString(
            artifact.resolve("maven-metadata.xml"),
            String.join(
                "\n",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<metadata>",
                "  <groupId>org.test</groupId>",
                "  <artifactId>idem</artifactId>",
                "  <versioning>",
                "    <versions>",
                "      <version>1.0.0</version>",
                "    </versions>",
                "  </versioning>",
                "</metadata>"
            ),
            StandardCharsets.UTF_8
        );
        final Path ver = artifact.resolve("1.0.0");
        Files.createDirectories(ver);
        Files.write(ver.resolve("idem-1.0.0.jar"), new byte[50]);
        final String[] args = {
            "--type", "maven",
            "--path", tmp.toString(),
            "--repo-name", repoName,
            "--db-url", dbUrl,
            "--db-user", dbUser,
            "--db-password", dbPassword,
            "--batch-size", "10",
        };
        final int firstRun = BackfillCli.run(args);
        MatcherAssert.assertThat(
            "First run should succeed",
            firstRun,
            Matchers.is(0)
        );
        final int secondRun = BackfillCli.run(args);
        MatcherAssert.assertThat(
            "Second run (upsert) should succeed",
            secondRun,
            Matchers.is(0)
        );
        final long count;
        try (Connection conn =
            DriverManager.getConnection(dbUrl, dbUser, dbPassword);
            Statement stmt = conn.createStatement();
            ResultSet rset = stmt.executeQuery(
                "SELECT count(*) FROM artifacts WHERE repo_name = '"
                    + repoName + "'"
            )) {
            rset.next();
            count = rset.getLong(1);
        }
        MatcherAssert.assertThat(
            "UPSERT should not duplicate; count should still be 1",
            count,
            Matchers.is(1L)
        );
    }
}
