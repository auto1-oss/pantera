/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer.cli;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class FailureTracker implements AutoCloseable {

    private final Path dir;
    private final Map<String, BufferedWriter> writers;

    FailureTracker(final Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
        this.writers = new ConcurrentHashMap<>();
    }

    void record(final String repo, final String path, final String reason) {
        try {
            final BufferedWriter writer = this.writers.computeIfAbsent(repo, this::openWriter);
            synchronized (writer) {
                writer.write(path);
                writer.write("|");
                writer.write(reason == null ? "" : reason);
                writer.newLine();
                writer.flush();
            }
        } catch (final IOException err) {
            throw new IllegalStateException(err);
        }
    }

    private BufferedWriter openWriter(final String repo) {
        try {
            final Path file = this.dir.resolve(repo + "-failures.log");
            return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (final IOException err) {
            throw new IllegalStateException(err);
        }
    }

    @Override
    public void close() throws IOException {
        for (final BufferedWriter writer : this.writers.values()) {
            writer.close();
        }
    }
}
