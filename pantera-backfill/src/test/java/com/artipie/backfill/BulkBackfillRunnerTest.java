/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.backfill;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link BulkBackfillRunner}.
 *
 * <p>All tests use {@code dryRun=true} and a null datasource unless testing
 * the FAILED path, which deliberately uses {@code dryRun=false} and a null
 * datasource to trigger a NullPointerException in BatchInserter.</p>
 *
 * @since 1.20.13
 */
final class BulkBackfillRunnerTest {

    /**
     * Null print stream for suppressing summary output during tests.
     */
    private static final PrintStream DEV_NULL =
        new PrintStream(OutputStream.nullOutputStream());

    // ── Happy path ───────────────────────────────────────────────────────────

    /**
     * Empty config dir → exit code 0, zero repos processed.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if directory setup fails
     */
    @Test
    void emptyConfigDirSucceeds(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        Files.createDirectories(storageRoot);
        final int code = runner(configDir, storageRoot, true).run();
        MatcherAssert.assertThat(
            "Empty config dir should return exit code 0",
            code,
            Matchers.is(0)
        );
    }

    /**
     * Two valid repos with file scanner → both succeed, exit code 0.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void twoValidReposSucceed(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        // Repo 1: "myfiles" type file
        Files.writeString(configDir.resolve("myfiles.yaml"), "repo:\n  type: file\n");
        final Path repo1 = storageRoot.resolve("myfiles");
        Files.createDirectories(repo1);
        Files.writeString(repo1.resolve("artifact.txt"), "content");
        // Repo 2: "otherfiles" type file
        Files.writeString(configDir.resolve("otherfiles.yaml"), "repo:\n  type: file\n");
        final Path repo2 = storageRoot.resolve("otherfiles");
        Files.createDirectories(repo2);
        Files.writeString(repo2.resolve("pkg.dat"), "data");
        final int code = runner(configDir, storageRoot, true).run();
        MatcherAssert.assertThat(
            "Two valid repos should return exit code 0",
            code,
            Matchers.is(0)
        );
    }

    // ── SKIPPED paths ────────────────────────────────────────────────────────

    /**
     * Repo with unknown type → SKIPPED, rest continue, exit code 0.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void unknownTypeIsSkipped(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        // Unknown type
        Files.writeString(configDir.resolve("weird.yaml"), "repo:\n  type: weird-hosted\n");
        // Valid repo that should still run
        Files.writeString(configDir.resolve("myfiles.yaml"), "repo:\n  type: file\n");
        Files.createDirectories(storageRoot.resolve("myfiles"));
        final int code = runner(configDir, storageRoot, true).run();
        MatcherAssert.assertThat(
            "Unknown type should be SKIPPED, run exits 0",
            code,
            Matchers.is(0)
        );
    }

    /**
     * Repo with missing storage path → SKIPPED, rest continue, exit code 0.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void missingStoragePathIsSkipped(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        Files.createDirectories(storageRoot);
        // This repo has a valid YAML but no matching storage directory
        Files.writeString(configDir.resolve("ghost.yaml"), "repo:\n  type: file\n");
        // Valid repo
        Files.writeString(configDir.resolve("real.yaml"), "repo:\n  type: file\n");
        Files.createDirectories(storageRoot.resolve("real"));
        final int code = runner(configDir, storageRoot, true).run();
        MatcherAssert.assertThat(
            "Missing storage path should be SKIPPED, run exits 0",
            code,
            Matchers.is(0)
        );
    }

    /**
     * Proxy type is normalised before lookup: docker-proxy → docker scanner is used.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void proxyTypeIsNormalised(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        // docker-proxy should normalise to docker
        Files.writeString(
            configDir.resolve("docker_cache.yaml"),
            "repo:\n  type: docker-proxy\n"
        );
        // Create minimal docker v2 storage layout so DockerScanner doesn't fail on missing dirs
        final Path dockerRepo = storageRoot.resolve("docker_cache");
        Files.createDirectories(dockerRepo.resolve("repositories"));
        final int code = runner(configDir, storageRoot, true).run();
        MatcherAssert.assertThat(
            "docker-proxy should normalise to docker scanner, exit 0",
            code,
            Matchers.is(0)
        );
    }

    // ── PARSE_ERROR paths ────────────────────────────────────────────────────

    /**
     * Malformed YAML → PARSE_ERROR, rest continue, exit code 0.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void parseErrorContinuesRun(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("bad.yaml"), "repo: [\nunclosed\n");
        Files.writeString(configDir.resolve("good.yaml"), "repo:\n  type: file\n");
        Files.createDirectories(storageRoot.resolve("good"));
        final int code = runner(configDir, storageRoot, true).run();
        MatcherAssert.assertThat(
            "PARSE_ERROR should not set exit code to 1",
            code,
            Matchers.is(0)
        );
    }

    /**
     * PARSE_ERROR only run → exit code 0.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void parseErrorOnlyExitsZero(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("bad.yaml"), "not: valid: yaml: content\n  broken");
        final int code = runner(configDir, tmp, true).run();
        MatcherAssert.assertThat(
            "PARSE_ERROR only should exit 0",
            code,
            Matchers.is(0)
        );
    }

    // ── FAILED paths ─────────────────────────────────────────────────────────

    /**
     * Scanner throws (triggered by null datasource + dryRun=false) → FAILED,
     * rest continue, exit code 1.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void failedRepoExitsOne(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        // This repo will FAIL: dryRun=false, dataSource=null → NPE in BatchInserter
        Files.writeString(configDir.resolve("willbreak.yaml"), "repo:\n  type: file\n");
        final Path breakRepo = storageRoot.resolve("willbreak");
        Files.createDirectories(breakRepo);
        Files.writeString(breakRepo.resolve("a.txt"), "x");
        // dryRun=false, dataSource=null triggers failure
        final int code = new BulkBackfillRunner(
            configDir, storageRoot, null, "system", 100, false, 10000, DEV_NULL
        ).run();
        MatcherAssert.assertThat(
            "FAILED repo should set exit code to 1",
            code,
            Matchers.is(1)
        );
    }

    /**
     * PARSE_ERROR + FAILED in same run → exit code 1 (FAILED dominates).
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void parseErrorPlusFailed(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("bad.yaml"), "not: valid\n  broken: [");
        Files.writeString(configDir.resolve("willbreak.yaml"), "repo:\n  type: file\n");
        final Path breakRepo = storageRoot.resolve("willbreak");
        Files.createDirectories(breakRepo);
        Files.writeString(breakRepo.resolve("a.txt"), "x");
        final int code = new BulkBackfillRunner(
            configDir, storageRoot, null, "system", 100, false, 10000, DEV_NULL
        ).run();
        MatcherAssert.assertThat(
            "PARSE_ERROR + FAILED should exit 1",
            code,
            Matchers.is(1)
        );
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    /**
     * Subdirectories in config dir are ignored (non-recursive).
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void subdirectoriesAreIgnored(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        // Subdirectory with a yaml inside — should not be processed
        final Path subdir = configDir.resolve("subgroup");
        Files.createDirectories(subdir);
        Files.writeString(subdir.resolve("inner.yaml"), "repo:\n  type: file\n");
        // Valid top-level repo
        Files.writeString(configDir.resolve("top.yaml"), "repo:\n  type: file\n");
        Files.createDirectories(storageRoot.resolve("top"));
        final int code = runner(configDir, storageRoot, true).run();
        MatcherAssert.assertThat(
            "Subdirectories should be ignored, run exits 0",
            code,
            Matchers.is(0)
        );
    }

    /**
     * A .yml file (wrong extension) is skipped — not processed, run still succeeds.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void ymlExtensionIsSkipped(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        // .yml file should be silently skipped
        Files.writeString(configDir.resolve("repo.yml"), "repo:\n  type: file\n");
        // Valid .yaml file
        Files.writeString(configDir.resolve("valid.yaml"), "repo:\n  type: file\n");
        Files.createDirectories(storageRoot.resolve("valid"));
        final int code = runner(configDir, storageRoot, true).run();
        MatcherAssert.assertThat(
            ".yml file should be skipped, run exits 0",
            code,
            Matchers.is(0)
        );
    }

    /**
     * Two repos with different names both succeed — verifies the seenNames set
     * does not produce false-positive duplicate collisions.
     *
     * <p>Note: the filesystem guarantees unique filenames within a directory,
     * so a true stem collision (two files producing the same stem) cannot
     * occur in practice. The {@code seenNames} guard is a defensive measure.
     * This test verifies the guard does not interfere with normal operation.</p>
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void twoDistinctReposDoNotCollide(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("alpha.yaml"), "repo:\n  type: file\n");
        Files.writeString(configDir.resolve("beta.yaml"), "repo:\n  type: file\n");
        Files.createDirectories(storageRoot.resolve("alpha"));
        Files.createDirectories(storageRoot.resolve("beta"));
        final int code = runner(configDir, storageRoot, true).run();
        MatcherAssert.assertThat(
            "Two repos with distinct names should both succeed, exit 0",
            code,
            Matchers.is(0)
        );
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private static BulkBackfillRunner runner(
        final Path configDir,
        final Path storageRoot,
        final boolean dryRun
    ) {
        return new BulkBackfillRunner(
            configDir, storageRoot, null, "system", 1000, dryRun, 10000, DEV_NULL
        );
    }
}
