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
package com.auto1.pantera.backfill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link BackfillCli}.
 *
 * <p>All tests exercise the {@code run()} method which returns an
 * exit code (0 = success, 1 = error) instead of calling
 * {@code System.exit()}.</p>
 *
 * @since 1.20.13
 */
final class BackfillCliTest {

    /**
     * Dry-run with a file scanner should succeed (exit code 0) and
     * process all non-hidden regular files in the temp directory.
     *
     * @param tmp Temporary directory created by JUnit
     * @throws IOException If temp file creation fails
     */
    @Test
    void dryRunWithFileScanner(@TempDir final Path tmp) throws IOException {
        Files.createFile(tmp.resolve("file1.txt"));
        Files.write(tmp.resolve("file2.dat"), new byte[]{1, 2, 3});
        Files.createFile(tmp.resolve(".hidden"));
        final int code = BackfillCli.run(
            "--type", "file",
            "--path", tmp.toString(),
            "--repo-name", "test",
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "Dry-run with file scanner should succeed",
            code,
            Matchers.is(0)
        );
    }

    /**
     * Running with no arguments should fail (exit code 1) because
     * required options are missing.
     */
    @Test
    void missingRequiredArgs() {
        final int code = BackfillCli.run();
        MatcherAssert.assertThat(
            "Missing required args should return exit code 1",
            code,
            Matchers.is(1)
        );
    }

    /**
     * Running with a non-existent path should fail (exit code 1).
     */
    @Test
    void invalidPath() {
        final int code = BackfillCli.run(
            "--type", "file",
            "--path", "/nonexistent/directory/that/does/not/exist",
            "--repo-name", "test",
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "Non-existent path should return exit code 1",
            code,
            Matchers.is(1)
        );
    }

    /**
     * Running with an unknown scanner type should fail (exit code 1).
     *
     * @param tmp Temporary directory created by JUnit
     */
    @Test
    void invalidType(@TempDir final Path tmp) {
        final int code = BackfillCli.run(
            "--type", "unknown_type_xyz",
            "--path", tmp.toString(),
            "--repo-name", "test",
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "Unknown scanner type should return exit code 1",
            code,
            Matchers.is(1)
        );
    }

    /**
     * Running with --help should succeed (exit code 0).
     */
    @Test
    void helpFlag() {
        final int code = BackfillCli.run("--help");
        MatcherAssert.assertThat(
            "Help flag should return exit code 0",
            code,
            Matchers.is(0)
        );
    }

    /**
     * Running without --db-url and without --dry-run should fail
     * (exit code 1) because the database URL is required for real runs.
     *
     * @param tmp Temporary directory created by JUnit
     */
    @Test
    void dbUrlRequiredWithoutDryRun(@TempDir final Path tmp) {
        final int code = BackfillCli.run(
            "--type", "file",
            "--path", tmp.toString(),
            "--repo-name", "test"
        );
        MatcherAssert.assertThat(
            "Missing --db-url without --dry-run should return exit code 1",
            code,
            Matchers.is(1)
        );
    }

    /**
     * Dry-run with nested directories should process files recursively
     * and skip hidden files.
     *
     * @param tmp Temporary directory created by JUnit
     * @throws IOException If temp file creation fails
     */
    @Test
    void dryRunWithNestedDirectories(@TempDir final Path tmp)
        throws IOException {
        final Path sub = tmp.resolve("subdir");
        Files.createDirectory(sub);
        Files.createFile(tmp.resolve("root-file.txt"));
        Files.createFile(sub.resolve("nested-file.txt"));
        Files.createFile(sub.resolve(".hidden-nested"));
        final int code = BackfillCli.run(
            "--type", "file",
            "--path", tmp.toString(),
            "--repo-name", "nested-test",
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "Dry-run with nested directories should succeed",
            code,
            Matchers.is(0)
        );
    }

    /**
     * --config-dir without --storage-root should fail (exit code 1).
     *
     * @param tmp JUnit temp directory
     * @throws IOException if directory setup fails
     */
    @Test
    void configDirWithoutStorageRootFails(@TempDir final Path tmp)
        throws IOException {
        Files.createDirectories(tmp);
        final int code = BackfillCli.run(
            "--config-dir", tmp.toString(),
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "--config-dir without --storage-root should return exit code 1",
            code,
            Matchers.is(1)
        );
    }

    /**
     * --storage-root without --config-dir should fail (exit code 1).
     *
     * @param tmp JUnit temp directory
     * @throws IOException if directory setup fails
     */
    @Test
    void storageRootWithoutConfigDirFails(@TempDir final Path tmp)
        throws IOException {
        Files.createDirectories(tmp);
        final int code = BackfillCli.run(
            "--storage-root", tmp.toString(),
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "--storage-root without --config-dir should return exit code 1",
            code,
            Matchers.is(1)
        );
    }

    /**
     * --config-dir combined with --type should fail (mutually exclusive).
     *
     * @param tmp JUnit temp directory
     * @throws IOException if directory setup fails
     */
    @Test
    void configDirAndTypeTogether(@TempDir final Path tmp) throws IOException {
        Files.createDirectories(tmp);
        final int code = BackfillCli.run(
            "--config-dir", tmp.toString(),
            "--storage-root", tmp.toString(),
            "--type", "file",
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "--config-dir and --type together should return exit code 1",
            code,
            Matchers.is(1)
        );
    }

    /**
     * Valid --config-dir + --storage-root in dry-run mode → exit code 0.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void bulkModeWithConfigDirSucceeds(@TempDir final Path tmp)
        throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        Files.createDirectories(storageRoot);
        Files.writeString(configDir.resolve("myrepo.yaml"), "repo:\n  type: file\n");
        Files.createDirectories(storageRoot.resolve("myrepo"));
        Files.writeString(storageRoot.resolve("myrepo").resolve("f.txt"), "hi");
        final int code = BackfillCli.run(
            "--config-dir", configDir.toString(),
            "--storage-root", storageRoot.toString(),
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "Valid bulk mode dry-run should return exit code 0",
            code,
            Matchers.is(0)
        );
    }

    /**
     * --type alone without --path and --repo-name should fail (exit code 1).
     *
     * @throws IOException if test setup fails
     */
    @Test
    void typeWithoutPathAndRepoNameFails() throws IOException {
        final int code = BackfillCli.run(
            "--type", "file",
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "--type without --path and --repo-name should return exit code 1",
            code,
            Matchers.is(1)
        );
    }
}
