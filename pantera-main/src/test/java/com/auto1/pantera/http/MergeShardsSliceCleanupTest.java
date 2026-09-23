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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ValueNotFoundException;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * B104: the post-merge cleanup of {@code .import} / {@code .meta} must not
 * report a failure when there is nothing to delete. Storage has no value at
 * a directory key ({@code FileStorage} answers {@link ValueNotFoundException}),
 * so "not found" is the normal outcome of a no-op merge.
 */
final class MergeShardsSliceCleanupTest {

    private static final String CAP = "MergeShardsSliceCleanupCap";

    private static final String HTTP_LOG = "com.auto1.pantera.http";

    private Capture capture;

    @BeforeEach
    void setUp() {
        this.capture = new Capture(CAP);
        this.capture.start();
        Configurator.setLevel(HTTP_LOG, Level.DEBUG);
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final Configuration cfg = ctx.getConfiguration();
        cfg.addAppender(this.capture);
        cfg.getLoggerConfig(HTTP_LOG).addAppender(this.capture, null, null);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().getLoggerConfig(HTTP_LOG).removeAppender(CAP);
        this.capture.stop();
        ctx.updateLoggers();
    }

    @Test
    void cleanupWithNothingToDeleteLogsNoWarning() {
        MergeShardsSlice.cleanupTempFolders(new DirectoryLessStorage(new InMemoryStorage()))
            .toCompletableFuture().join();
        MatcherAssert.assertThat(
            this.capture.warnings(), new IsEqual<>(List.of())
        );
    }

    /**
     * Storage that, like {@code FileStorage}, answers
     * {@link ValueNotFoundException} for a key that holds no value.
     */
    private static final class DirectoryLessStorage extends Storage.Wrap {

        private final Storage origin;

        DirectoryLessStorage(final Storage origin) {
            super(origin);
            this.origin = origin;
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            return this.origin.exists(key).thenCompose(
                present -> present
                    ? this.origin.delete(key)
                    : CompletableFuture.failedFuture(new ValueNotFoundException(key))
            );
        }
    }

    /**
     * Captures WARN-or-worse records of the http logger.
     */
    private static final class Capture extends AbstractAppender {

        private final List<String> warns = new ArrayList<>();

        Capture(final String name) {
            super(name, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(final LogEvent event) {
            if (HTTP_LOG.equals(event.getLoggerName())
                && event.getLevel().isMoreSpecificThan(Level.WARN)) {
                synchronized (this.warns) {
                    this.warns.add(event.getMessage().getFormattedMessage());
                }
            }
        }

        List<String> warnings() {
            synchronized (this.warns) {
                return List.copyOf(this.warns);
            }
        }
    }
}
