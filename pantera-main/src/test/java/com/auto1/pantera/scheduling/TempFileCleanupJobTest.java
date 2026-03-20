/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.scheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link TempFileCleanupJob}.
 *
 * @since 1.20.13
 */
public final class TempFileCleanupJobTest {

    @Test
    void deletesOldTmpFiles(@TempDir final Path dir) throws Exception {
        final Path old = dir.resolve("artipie-stc-abc123.tmp");
        Files.writeString(old, "old data");
        setOldTimestamp(old, 120);
        final Path recent = dir.resolve("artipie-stc-def456.tmp");
        Files.writeString(recent, "recent data");
        TempFileCleanupJob.cleanup(dir, 60L);
        Assertions.assertFalse(
            Files.exists(old),
            "Old .tmp file should have been deleted"
        );
        Assertions.assertTrue(
            Files.exists(recent),
            "Recent .tmp file should be kept"
        );
    }

    @Test
    void deletesOldPanteraCacheFiles(@TempDir final Path dir) throws Exception {
        final Path old = dir.resolve("artipie-cache-data");
        Files.writeString(old, "cache data");
        setOldTimestamp(old, 90);
        final Path recent = dir.resolve("artipie-cache-fresh");
        Files.writeString(recent, "fresh cache");
        TempFileCleanupJob.cleanup(dir, 60L);
        Assertions.assertFalse(
            Files.exists(old),
            "Old artipie-cache- file should have been deleted"
        );
        Assertions.assertTrue(
            Files.exists(recent),
            "Recent artipie-cache- file should be kept"
        );
    }

    @Test
    void deletesOldPartFiles(@TempDir final Path dir) throws Exception {
        final Path old = dir.resolve("data.part-abc123");
        Files.writeString(old, "partial data");
        setOldTimestamp(old, 90);
        final Path recent = dir.resolve("data.part-def456");
        Files.writeString(recent, "recent partial");
        TempFileCleanupJob.cleanup(dir, 60L);
        Assertions.assertFalse(
            Files.exists(old),
            "Old .part- file should have been deleted"
        );
        Assertions.assertTrue(
            Files.exists(recent),
            "Recent .part- file should be kept"
        );
    }

    @Test
    void deletesFilesInDotTmpSubdir(@TempDir final Path dir) throws Exception {
        final Path tmpDir = dir.resolve(".tmp");
        Files.createDirectories(tmpDir);
        final Path old = tmpDir.resolve("some-uuid-file");
        Files.writeString(old, "uuid data");
        setOldTimestamp(old, 120);
        final Path recent = tmpDir.resolve("another-uuid-file");
        Files.writeString(recent, "recent uuid data");
        TempFileCleanupJob.cleanup(dir, 60L);
        Assertions.assertFalse(
            Files.exists(old),
            "Old file in .tmp/ directory should have been deleted"
        );
        Assertions.assertTrue(
            Files.exists(recent),
            "Recent file in .tmp/ directory should be kept"
        );
    }

    @Test
    void keepsNonTempFiles(@TempDir final Path dir) throws Exception {
        final Path normal = dir.resolve("important-data.jar");
        Files.writeString(normal, "keep me");
        setOldTimestamp(normal, 120);
        final Path readme = dir.resolve("README.md");
        Files.writeString(readme, "keep me too");
        setOldTimestamp(readme, 120);
        TempFileCleanupJob.cleanup(dir, 60L);
        Assertions.assertTrue(
            Files.exists(normal),
            "Non-temp .jar file should not be deleted"
        );
        Assertions.assertTrue(
            Files.exists(readme),
            "Non-temp .md file should not be deleted"
        );
    }

    @Test
    void recursesIntoSubdirectories(@TempDir final Path dir) throws Exception {
        final Path sub = dir.resolve("subdir");
        Files.createDirectories(sub);
        final Path deep = sub.resolve("deep.tmp");
        Files.writeString(deep, "deep data");
        setOldTimestamp(deep, 120);
        TempFileCleanupJob.cleanup(dir, 60L);
        Assertions.assertFalse(
            Files.exists(deep),
            "Old .tmp file in subdirectory should have been deleted"
        );
    }

    @Test
    void handlesNonExistentDirectory() {
        final Path missing = Path.of("/nonexistent/dir/that/does/not/exist");
        Assertions.assertDoesNotThrow(
            () -> TempFileCleanupJob.cleanup(missing, 60L),
            "Job should handle non-existent directory gracefully"
        );
    }

    @Test
    void handlesNullDirectory() {
        Assertions.assertDoesNotThrow(
            () -> TempFileCleanupJob.cleanup(null, 60L),
            "Job should handle null directory gracefully"
        );
    }

    @Test
    void usesDefaultMaxAge(@TempDir final Path dir) throws Exception {
        final Path old = dir.resolve("artipie-stc-test.tmp");
        Files.writeString(old, "data");
        setOldTimestamp(old, 120);
        final Path recent = dir.resolve("artipie-stc-new.tmp");
        Files.writeString(recent, "new data");
        TempFileCleanupJob.cleanup(dir, TempFileCleanupJob.DEFAULT_MAX_AGE_MINUTES);
        Assertions.assertFalse(
            Files.exists(old),
            "File older than default 60 min should be deleted"
        );
        Assertions.assertTrue(
            Files.exists(recent),
            "Recent file should be kept with default max age"
        );
    }

    @Test
    void isTempFileMatchesCorrectPatterns() {
        Assertions.assertTrue(
            TempFileCleanupJob.isTempFile(Path.of("/tmp/artipie-stc-abc.tmp")),
            "Should match artipie-stc-*.tmp"
        );
        Assertions.assertTrue(
            TempFileCleanupJob.isTempFile(Path.of("/tmp/something.tmp")),
            "Should match *.tmp"
        );
        Assertions.assertTrue(
            TempFileCleanupJob.isTempFile(Path.of("/tmp/artipie-cache-data")),
            "Should match artipie-cache-*"
        );
        Assertions.assertTrue(
            TempFileCleanupJob.isTempFile(Path.of("/cache/.tmp/uuid-file")),
            "Should match files in .tmp/ directory"
        );
        Assertions.assertTrue(
            TempFileCleanupJob.isTempFile(Path.of("/tmp/data.part-abc123")),
            "Should match .part- files"
        );
        Assertions.assertFalse(
            TempFileCleanupJob.isTempFile(Path.of("/tmp/important.jar")),
            "Should not match .jar files"
        );
        Assertions.assertFalse(
            TempFileCleanupJob.isTempFile(Path.of("/data/artifact.pom")),
            "Should not match .pom files"
        );
    }

    @Test
    void deletesOldPanteraStcFilesWithoutTmpExtension(@TempDir final Path dir)
        throws Exception {
        final Path old = dir.resolve("artipie-stc-nosuffix");
        Files.writeString(old, "stc data");
        setOldTimestamp(old, 120);
        TempFileCleanupJob.cleanup(dir, 60L);
        Assertions.assertFalse(
            Files.exists(old),
            "Old artipie-stc- file without .tmp extension should have been deleted"
        );
    }

    /**
     * Sets the last modified time of a file to the given number of minutes in the past.
     *
     * @param file File to modify
     * @param minutesAgo How many minutes in the past
     * @throws IOException On error
     */
    private static void setOldTimestamp(final Path file, final int minutesAgo)
        throws IOException {
        Files.setLastModifiedTime(
            file,
            FileTime.from(Instant.now().minus(minutesAgo, ChronoUnit.MINUTES))
        );
    }
}
