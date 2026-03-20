/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.db;

import com.artipie.scheduling.ArtifactEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * Tests for {@link DeadLetterWriter}.
 *
 * @since 1.20.13
 */
final class DeadLetterWriterTest {

    @Test
    void writesEventsToFile(@TempDir final Path tmp) throws Exception {
        final DeadLetterWriter writer = new DeadLetterWriter(tmp.resolve("dead-letter"));
        final List<ArtifactEvent> events = List.of(
            new ArtifactEvent(
                "maven", "my-repo", "owner1",
                "com.example:artifact", "1.0.0",
                1024L, System.currentTimeMillis(),
                ArtifactEvent.Type.INSERT
            )
        );
        final Path file = writer.write(events, new RuntimeException("DB down"), 3);
        assertThat("Dead letter file should exist",
            String.valueOf(Files.exists(file)), containsString("true"));
        final String content = Files.readString(file);
        assertThat("Should contain repo name", content, containsString("my-repo"));
        assertThat("Should contain artifact name", content,
            containsString("com.example:artifact"));
        assertThat("Should contain error", content, containsString("DB down"));
        assertThat("Should contain retry count", content, containsString("3"));
    }

    @Test
    void createsDirectoryIfMissing(@TempDir final Path tmp) throws Exception {
        final Path nested = tmp.resolve("a").resolve("b").resolve("dead-letter");
        final DeadLetterWriter writer = new DeadLetterWriter(nested);
        final List<ArtifactEvent> events = List.of(
            new ArtifactEvent(
                "npm", "npm-proxy", "admin",
                "@scope/pkg", "2.0.0",
                0L, System.currentTimeMillis(),
                ArtifactEvent.Type.DELETE_VERSION
            )
        );
        final Path file = writer.write(events, new RuntimeException("timeout"), 1);
        assertThat("Nested directory should be created",
            String.valueOf(Files.isDirectory(nested)), containsString("true"));
        assertThat("File should exist",
            String.valueOf(Files.exists(file)), containsString("true"));
    }

    @Test
    void handlesMultipleEvents(@TempDir final Path tmp) throws Exception {
        final DeadLetterWriter writer = new DeadLetterWriter(tmp);
        final List<ArtifactEvent> events = List.of(
            new ArtifactEvent("maven", "r1", "u1",
                "a1", "1.0", 100L, System.currentTimeMillis(),
                ArtifactEvent.Type.INSERT),
            new ArtifactEvent("maven", "r1", "u2",
                "a2", "2.0", 200L, System.currentTimeMillis(),
                ArtifactEvent.Type.INSERT),
            new ArtifactEvent("docker", "r2", "u1",
                "a3", "3.0", 300L, System.currentTimeMillis(),
                ArtifactEvent.Type.DELETE_ALL)
        );
        final Path file = writer.write(events, new RuntimeException("fail"), 2);
        final String content = Files.readString(file);
        assertThat("Should contain first event", content, containsString("a1"));
        assertThat("Should contain second event", content, containsString("a2"));
        assertThat("Should contain third event", content, containsString("a3"));
        assertThat("Should contain event count", content, containsString("3"));
    }
}
