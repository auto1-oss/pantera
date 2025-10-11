/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer.cli;

import com.artipie.importer.api.ChecksumPolicy;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProgressTrackerTest {

    @TempDir
    Path temp;

    @Test
    void persistsAndResumes() throws Exception {
        final Path log = this.temp.resolve("progress.log");
        final Path file = this.temp.resolve("Maven/my-repo/com/acme/app/1.0/app.jar");
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, "data");
        final TaskScanner scanner = new TaskScanner(this.temp, "UNKNOWN", ChecksumPolicy.COMPUTE);
        final UploadTask task = scanner.analyze(file).orElseThrow();
        try (ProgressTracker tracker = new ProgressTracker(log, false)) {
            tracker.markCompleted(task);
        }
        try (ProgressTracker tracker = new ProgressTracker(log, true)) {
            final Set<String> completed = tracker.completedKeys();
            Assertions.assertTrue(completed.contains(task.idempotencyKey()));
        }
    }
}
