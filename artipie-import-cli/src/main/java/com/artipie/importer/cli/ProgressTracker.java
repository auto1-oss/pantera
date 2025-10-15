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
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class ProgressTracker implements AutoCloseable {

    private final Path log;
    private final BufferedWriter writer;
    private final Set<String> completed;
    private final Object lock;

    ProgressTracker(final Path log, final boolean resume) throws IOException {
        this.log = log;
        if (log.getParent() != null) {
            Files.createDirectories(log.getParent());
        }
        this.completed = Collections.synchronizedSet(new HashSet<>());
        this.lock = new Object();
        if (resume && Files.exists(log)) {
            System.out.println("Loading progress log...");
            final long start = System.currentTimeMillis();
            int count = 0;
            // Stream lines instead of loading entire file into memory
            try (var lines = Files.lines(log, StandardCharsets.UTF_8)) {
                count = (int) lines
                    .peek(line -> {
                        if (this.completed.size() % 10000 == 0 && this.completed.size() > 0) {
                            System.out.printf("  Loaded %d completed tasks...%n", this.completed.size());
                        }
                    })
                    .map(line -> {
                        final int idx = line.indexOf('|');
                        return idx > 0 ? line.substring(0, idx) : null;
                    })
                    .filter(key -> key != null)
                    .peek(this.completed::add)
                    .count();
            }
            final long elapsed = System.currentTimeMillis() - start;
            System.out.printf("Loaded %d completed tasks in %.2f seconds%n", count, elapsed / 1000.0);
        }
        this.writer = Files.newBufferedWriter(log, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    Set<String> completedKeys() {
        return this.completed;
    }

    void markCompleted(final UploadTask task) {
        if (this.completed.add(task.idempotencyKey())) {
            try {
                synchronized (this.lock) {
                    this.writer.write(task.idempotencyKey());
                    this.writer.write('|');
                    this.writer.write(task.repoName());
                    this.writer.write('|');
                    this.writer.write(task.relativePath());
                    this.writer.newLine();
                    this.writer.flush();
                }
            } catch (final IOException err) {
                throw new IllegalStateException(err);
            }
        }
    }

    @Override
    public void close() throws IOException {
        this.writer.close();
    }
}
