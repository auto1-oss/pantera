/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer.cli;

import com.artipie.importer.api.ChecksumPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TaskScannerTest {

    @TempDir
    Path temp;

    @Test
    void detectsMavenArtifact() throws Exception {
        final Path file = this.temp.resolve("Maven/my-repo/com/example/demo/1.0/demo-1.0.jar");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "content");
        final TaskScanner scanner = new TaskScanner(this.temp, "UNKNOWN", ChecksumPolicy.COMPUTE);
        final Optional<UploadTask> task = scanner.analyze(file);
        Assertions.assertTrue(task.isPresent());
        Assertions.assertEquals("my-repo", task.get().repoName());
        Assertions.assertEquals("maven", task.get().repoType());
    }

    @Test
    void skipsUnknownLayout() throws Exception {
        final Path file = this.temp.resolve("misc/readme.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "docs");
        final TaskScanner scanner = new TaskScanner(this.temp, "UNKNOWN", ChecksumPolicy.COMPUTE);
        Assertions.assertTrue(scanner.analyze(file).isEmpty());
    }
}
