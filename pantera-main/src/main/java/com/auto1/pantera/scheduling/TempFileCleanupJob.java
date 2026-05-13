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
package com.auto1.pantera.scheduling;

import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.trace.SpanContext;
import java.io.IOException;
import org.slf4j.MDC;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * Quartz job that periodically scans a directory for stale temporary files
 * created during proxy cache operations and deletes them.
 * <p>
 * Pantera creates temp files in several places:
 * <ul>
 *   <li>{@code DiskCacheStorage} - UUID-named files in {@code .tmp/} subdirectory</li>
 *   <li>{@code StreamThroughCache} - files with prefix {@code pantera-stc-} and
 *       suffix {@code .tmp}</li>
 *   <li>Other operations that may leave {@code pantera-*} prefixed files in the
 *       system temp directory</li>
 * </ul>
 * <p>
 * These temp files can accumulate if processes crash before cleanup. This job
 * walks the configured directory recursively and deletes files matching known
 * temp file patterns that are older than a configurable age threshold.
 * <p>
 * Configuration via {@link JobDataMap}:
 * <ul>
 *   <li>{@code cleanupDir} - {@link Path} or {@link String} path to the directory
 *       to scan (required)</li>
 *   <li>{@code maxAgeMinutes} - {@link Long} maximum file age in minutes before
 *       deletion (default: 60)</li>
 * </ul>
 * <p>
 * Example scheduling with {@link QuartzService}:
 * <pre>{@code
 * JobDataMap data = new JobDataMap();
 * data.put("cleanupDir", Path.of("/tmp"));
 * data.put("maxAgeMinutes", 60L);
 * quartzService.schedulePeriodicJob(
 *     3600, 1, TempFileCleanupJob.class, data
 * );
 * }</pre>
 *
 * @since 1.20.13
 */
public final class TempFileCleanupJob implements Job {

    /**
     * Key for the cleanup directory in the {@link JobDataMap}.
     */
    public static final String CLEANUP_DIR_KEY = "cleanupDir";

    /**
     * Key for the maximum file age in minutes in the {@link JobDataMap}.
     */
    public static final String MAX_AGE_MINUTES_KEY = "maxAgeMinutes";

    /**
     * Default maximum age in minutes for temp files before they are deleted.
     */
    static final long DEFAULT_MAX_AGE_MINUTES = 60L;

    @Override
    public void execute(final JobExecutionContext context) throws JobExecutionException {
        MDC.put(EcsMdc.TRACE_ID, SpanContext.generateHex16());
        MDC.put(EcsMdc.SPAN_ID, SpanContext.generateHex16());
        try {
            final JobDataMap data = context.getMergedJobDataMap();
            final Path dir = resolveCleanupDir(data);
            final long max = data.containsKey(MAX_AGE_MINUTES_KEY)
                ? data.getLong(MAX_AGE_MINUTES_KEY)
                : DEFAULT_MAX_AGE_MINUTES;
            cleanup(dir, max);
        } finally {
            MDC.remove(EcsMdc.TRACE_ID);
            MDC.remove(EcsMdc.SPAN_ID);
        }
    }

    /**
     * Performs the temp file cleanup for the given directory with the given max age.
     * This method contains the core cleanup logic and can be called directly
     * for testing without requiring a Quartz execution context.
     *
     * @param dir Directory to scan for stale temp files, or null if not configured
     * @param maxAgeMinutes Maximum file age in minutes before deletion
     */
    static void cleanup(final Path dir, final long maxAgeMinutes) {
        if (dir == null) {
            EcsLogger.warn("com.auto1.pantera.scheduling")
                .message("TempFileCleanupJob: no cleanupDir configured, skipping")
                .eventCategory("process")
                .eventAction("temp_cleanup")
                .eventOutcome("failure")
                .log();
            return;
        }
        if (!Files.isDirectory(dir)) {
            EcsLogger.debug("com.auto1.pantera.scheduling")
                .message(
                    String.format(
                        "TempFileCleanupJob: directory does not exist: %s", dir
                    )
                )
                .eventCategory("process")
                .eventAction("temp_cleanup")
                .eventOutcome("failure")
                .log();
            return;
        }
        final long cutoff = Instant.now()
            .minusMillis(TimeUnit.MINUTES.toMillis(maxAgeMinutes))
            .toEpochMilli();
        final AtomicInteger deleted = new AtomicInteger(0);
        final AtomicInteger failed = new AtomicInteger(0);
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(
                    final Path file, final BasicFileAttributes attrs
                ) {
                    if (isTempFile(file) && isStale(attrs, cutoff)) {
                        try {
                            Files.deleteIfExists(file);
                            deleted.incrementAndGet();
                            EcsLogger.debug("com.auto1.pantera.scheduling")
                                .message(
                                    String.format(
                                        "TempFileCleanupJob: deleted stale temp file: %s",
                                        file
                                    )
                                )
                                .eventCategory("process")
                                .eventAction("temp_cleanup_delete")
                                .eventOutcome("success")
                                .log();
                        } catch (final IOException ex) {
                            failed.incrementAndGet();
                            EcsLogger.warn("com.auto1.pantera.scheduling")
                                .message(
                                    String.format(
                                        "TempFileCleanupJob: failed to delete: %s", file
                                    )
                                )
                                .eventCategory("process")
                                .eventAction("temp_cleanup_delete")
                                .eventOutcome("failure")
                                .error(ex)
                                .log();
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(
                    final Path file, final IOException exc
                ) {
                    EcsLogger.warn("com.auto1.pantera.scheduling")
                        .message(
                            String.format(
                                "TempFileCleanupJob: cannot access file: %s", file
                            )
                        )
                        .eventCategory("process")
                        .eventAction("temp_cleanup")
                        .eventOutcome("failure")
                        .error(exc)
                        .log();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException ex) {
            EcsLogger.error("com.auto1.pantera.scheduling")
                .message(
                    String.format(
                        "TempFileCleanupJob: error walking directory: %s", dir
                    )
                )
                .eventCategory("process")
                .eventAction("temp_cleanup")
                .eventOutcome("failure")
                .error(ex)
                .log();
        }
        EcsLogger.info("com.auto1.pantera.scheduling")
            .message(
                String.format(
                    "TempFileCleanupJob: completed scan of %s, deleted %d stale temp files, %d failures",
                    dir, deleted.get(), failed.get()
                )
            )
            .eventCategory("process")
            .eventAction("temp_cleanup")
            .eventOutcome("success")
            .log();
    }

    /**
     * Determines whether a file matches known Pantera temp file patterns.
     * <p>
     * Patterns matched:
     * <ul>
     *   <li>Files ending with {@code .tmp} (e.g., {@code pantera-stc-*.tmp})</li>
     *   <li>Files with names starting with {@code pantera-cache-}</li>
     *   <li>Files with names starting with {@code pantera-stc-}</li>
     *   <li>Files inside a directory named {@code .tmp} (DiskCacheStorage pattern)</li>
     *   <li>Files containing {@code .part-} in the name (failed partial writes)</li>
     * </ul>
     *
     * @param file Path to check
     * @return True if the file matches a known temp file pattern
     */
    static boolean isTempFile(final Path file) {
        final String name = file.getFileName().toString();
        final boolean intmpdir = file.getParent() != null
            && file.getParent().getFileName() != null
            && ".tmp".equals(file.getParent().getFileName().toString());
        return name.endsWith(".tmp")
            || name.startsWith("pantera-cache-")
            || name.startsWith("pantera-stc-")
            || intmpdir
            || name.contains(".part-");
    }

    /**
     * Checks whether file attributes indicate the file is older than the cutoff time.
     *
     * @param attrs File attributes
     * @param cutoff Cutoff time in epoch milliseconds
     * @return True if the file's last modified time is before the cutoff
     */
    private static boolean isStale(final BasicFileAttributes attrs, final long cutoff) {
        return attrs.lastModifiedTime().toMillis() < cutoff;
    }

    /**
     * Resolves the cleanup directory from the job data map.
     * Accepts both {@link Path} and {@link String} values.
     *
     * @param data Job data map
     * @return Resolved path, or null if not configured
     */
    private static Path resolveCleanupDir(final JobDataMap data) {
        final Object raw = data.get(CLEANUP_DIR_KEY);
        final Path result;
        if (raw instanceof Path) {
            result = (Path) raw;
        } else if (raw instanceof String) {
            result = Path.of((String) raw);
        } else {
            result = null;
        }
        return result;
    }
}
