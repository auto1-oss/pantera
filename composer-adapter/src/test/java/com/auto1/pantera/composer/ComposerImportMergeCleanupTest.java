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
package com.auto1.pantera.composer;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ValueNotFoundException;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
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
 * B104: a metadata merge with nothing to clean up must not report failures.
 * Storage has no directory values ({@code FileStorage} answers
 * {@link ValueNotFoundException} for a directory key), so "not found" while
 * cleaning the staging area is the normal outcome, not a WARN.
 */
final class ComposerImportMergeCleanupTest {

    private static final String CAP = "ComposerImportMergeCleanupCap";

    private static final String COMPOSER_LOG = "com.auto1.pantera.composer";

    private Capture capture;

    @BeforeEach
    void setUp() {
        this.capture = new Capture(CAP);
        this.capture.start();
        Configurator.setLevel(COMPOSER_LOG, Level.DEBUG);
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final Configuration cfg = ctx.getConfiguration();
        cfg.addAppender(this.capture);
        cfg.getLoggerConfig(COMPOSER_LOG).addAppender(this.capture, null, null);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().getLoggerConfig(COMPOSER_LOG).removeAppender(CAP);
        this.capture.stop();
        ctx.updateLoggers();
    }

    @Test
    void noOpMergeDeletesNothing() {
        final DirectoryLessStorage storage = new DirectoryLessStorage(new InMemoryStorage());
        final ComposerImportMerge.MergeResult result =
            new ComposerImportMerge(storage, Optional.empty()).mergeAll()
                .toCompletableFuture().join();
        MatcherAssert.assertThat(
            "nothing was merged",
            result.mergedPackages, new IsEqual<>(0L)
        );
        MatcherAssert.assertThat(
            "an empty staging area is not cleaned up",
            storage.deletes(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "a no-op merge logs no warning",
            this.capture.warnings(), new IsEqual<>(List.of())
        );
    }

    @Test
    void stagingRootWithoutValueIsNotAFailure() {
        final DirectoryLessStorage storage = new DirectoryLessStorage(new InMemoryStorage());
        storage.save(
            new Key.From(".versions", "acme-lib", "1.0.0.json"),
            new Content.From(
                "{\"packages\":{\"acme/lib\":{\"1.0.0\":{\"name\":\"acme/lib\",\"version\":\"1.0.0\"}}}}"
                    .getBytes(StandardCharsets.UTF_8)
            )
        ).join();
        final ComposerImportMerge.MergeResult result =
            new ComposerImportMerge(storage, Optional.empty()).mergeAll()
                .toCompletableFuture().join();
        MatcherAssert.assertThat(
            "the staged package was merged",
            result.mergedPackages, new IsEqual<>(1L)
        );
        MatcherAssert.assertThat(
            "the staged version file was removed",
            storage.exists(new Key.From(".versions", "acme-lib", "1.0.0.json")).join(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the directory-key 'not found' during cleanup logs no warning",
            this.capture.warnings(), new IsEqual<>(List.of())
        );
    }

    /**
     * Storage that, like {@code FileStorage}, has no value at a directory
     * key and answers {@link ValueNotFoundException} when asked to delete
     * one. Counts delete calls.
     */
    private static final class DirectoryLessStorage extends Storage.Wrap {

        private final Storage origin;

        private final AtomicInteger count = new AtomicInteger();

        DirectoryLessStorage(final Storage origin) {
            super(origin);
            this.origin = origin;
        }

        int deletes() {
            return this.count.get();
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            this.count.incrementAndGet();
            return this.origin.exists(key).thenCompose(
                present -> present
                    ? this.origin.delete(key)
                    : CompletableFuture.failedFuture(new ValueNotFoundException(key))
            );
        }
    }

    /**
     * Captures WARN-or-worse records of the composer logger.
     */
    private static final class Capture extends AbstractAppender {

        private final List<String> warns = new ArrayList<>();

        Capture(final String name) {
            super(name, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(final LogEvent event) {
            if (COMPOSER_LOG.equals(event.getLoggerName())
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
