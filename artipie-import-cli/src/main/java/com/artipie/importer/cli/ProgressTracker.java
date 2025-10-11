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
            Files.readAllLines(log, StandardCharsets.UTF_8).forEach(line -> {
                final int idx = line.indexOf('|');
                if (idx > 0) {
                    this.completed.add(line.substring(0, idx));
                }
            });
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
