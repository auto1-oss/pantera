/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.db;

import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.scheduling.ArtifactEvent;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes failed database events to a dead-letter JSON file.
 * Events that cannot be persisted after all retries are written here
 * for manual review and replay.
 *
 * @since 1.20.13
 */
public final class DeadLetterWriter {

    /**
     * Base directory for dead-letter files.
     */
    private final Path baseDir;

    /**
     * Constructor.
     * @param baseDir Base directory (e.g., /var/artipie/.dead-letter/)
     */
    public DeadLetterWriter(final Path baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * Write failed events to a dead-letter file.
     *
     * @param events Events that failed to persist
     * @param error The error that caused the failure
     * @param retryCount Number of retries attempted
     * @return Path of the dead-letter file written
     * @throws IOException If writing fails
     */
    public Path write(final List<ArtifactEvent> events, final Throwable error,
        final int retryCount) throws IOException {
        Files.createDirectories(this.baseDir);
        final String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        final String filename = String.format("db-events-%s.json",
            timestamp.replace(':', '-'));
        final Path file = this.baseDir.resolve(filename);
        final JsonObjectBuilder root = Json.createObjectBuilder()
            .add("timestamp", timestamp)
            .add("retryCount", retryCount)
            .add("error", error.toString())
            .add("eventCount", events.size());
        final JsonArrayBuilder eventsArray = Json.createArrayBuilder();
        for (final ArtifactEvent event : events) {
            eventsArray.add(
                Json.createObjectBuilder()
                    .add("repoType", event.repoType())
                    .add("repoName", event.repoName())
                    .add("artifactName", event.artifactName())
                    .add("version", event.artifactVersion())
                    .add("owner", event.owner())
                    .add("size", event.size())
                    .add("created", event.createdDate())
                    .add("eventType", event.eventType().name())
            );
        }
        root.add("events", eventsArray);
        try (Writer writer = Files.newBufferedWriter(file,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            Json.createWriter(writer).writeObject(root.build());
        }
        EcsLogger.error("com.auto1.pantera.db")
            .message(String.format("Wrote %d failed events to dead-letter file: %s",
                events.size(), file))
            .eventCategory("database")
            .eventAction("dead_letter_write")
            .eventOutcome("success")
            .field("file.path", file.toString())
            .log();
        return file;
    }
}
