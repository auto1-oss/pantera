/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.backfill;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates a bulk backfill run over a directory of Artipie repo configs.
 *
 * <p>For each {@code *.yaml} file found (non-recursively, sorted alphabetically)
 * in the config directory, derives the repo name from the filename stem and the
 * scanner type from {@code repo.type}, then runs the appropriate {@link Scanner}
 * against {@code storageRoot/<repoName>/}.</p>
 *
 * <p>Per-repo failures (parse errors, unknown types, missing storage, scan
 * exceptions) are all non-fatal: they are logged, recorded in the summary,
 * and the next repo is processed. Only a {@code FAILED} status (scan exception)
 * contributes to a non-zero exit code.</p>
 *
 * @since 1.20.13
 */
@SuppressWarnings("PMD.ExcessiveImports")
final class BulkBackfillRunner {

    /**
     * SLF4J logger.
     */
    private static final Logger LOG =
        LoggerFactory.getLogger(BulkBackfillRunner.class);

    /**
     * {@code .yaml} file extension constant.
     */
    private static final String YAML_EXT = ".yaml";

    /**
     * Directory containing {@code *.yaml} Artipie repo config files.
     */
    private final Path configDir;

    /**
     * Root directory under which each repo's data lives at
     * {@code <storageRoot>/<repoName>/}.
     */
    private final Path storageRoot;

    /**
     * Shared JDBC data source. May be {@code null} when {@code dryRun} is
     * {@code true}.
     */
    private final DataSource dataSource;

    /**
     * Owner string applied to all inserted artifact records.
     */
    private final String owner;

    /**
     * Batch insert size.
     */
    private final int batchSize;

    /**
     * If {@code true} count records but do not write to the database.
     */
    private final boolean dryRun;

    /**
     * Progress log interval (log every N records per repo).
     */
    private final int logInterval;

    /**
     * Print stream for the summary table (typically {@code System.err}).
     */
    private final PrintStream out;

    /**
     * Ctor.
     *
     * @param configDir Directory of repo YAML configs
     * @param storageRoot Root for repo storage directories
     * @param dataSource JDBC data source (may be null when dryRun=true)
     * @param owner Owner string for artifact records
     * @param batchSize JDBC batch insert size
     * @param dryRun If true, count only, no DB writes
     * @param logInterval Progress log every N records
     * @param out Stream for summary output (typically System.err)
     * @checkstyle ParameterNumberCheck (10 lines)
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    BulkBackfillRunner(
        final Path configDir,
        final Path storageRoot,
        final DataSource dataSource,
        final String owner,
        final int batchSize,
        final boolean dryRun,
        final int logInterval,
        final PrintStream out
    ) {
        this.configDir = configDir;
        this.storageRoot = storageRoot;
        this.dataSource = dataSource;
        this.owner = owner;
        this.batchSize = batchSize;
        this.dryRun = dryRun;
        this.logInterval = logInterval;
        this.out = out;
    }

    /**
     * Run the bulk backfill over all {@code *.yaml} files in the config
     * directory.
     *
     * @return Exit code: {@code 0} if all repos succeeded or were
     *     skipped/parse-errored, {@code 1} if any repo had a scan failure
     * @throws IOException if the config directory cannot be listed
     */
    int run() throws IOException {
        final List<RepoResult> results = new ArrayList<>();
        final Set<String> seenNames = new HashSet<>();
        final List<Path> yamlFiles = new ArrayList<>();
        try (Stream<Path> listing = Files.list(this.configDir)) {
            listing
                .filter(Files::isRegularFile)
                .forEach(p -> {
                    final String name = p.getFileName().toString();
                    if (name.endsWith(YAML_EXT)) {
                        yamlFiles.add(p);
                    } else if (name.endsWith(".yml")) {
                        LOG.debug(
                            "Skipping '{}' — use .yaml extension, not .yml",
                            p.getFileName()
                        );
                    }
                });
        }
        yamlFiles.sort(Path::compareTo);
        for (final Path file : yamlFiles) {
            results.add(this.processFile(file, seenNames));
        }
        this.printSummary(results);
        return results.stream()
            .anyMatch(r -> r.status().startsWith("FAILED")) ? 1 : 0;
    }

    /**
     * Process one YAML file and return a result row.
     *
     * @param file Path to the {@code .yaml} file
     * @param seenNames Set of repo name stems already processed
     * @return Result row for the summary table
     */
    private RepoResult processFile(
        final Path file,
        final Set<String> seenNames
    ) {
        final String fileName = file.getFileName().toString();
        final String stem = fileName.endsWith(YAML_EXT)
            ? fileName.substring(0, fileName.length() - YAML_EXT.length())
            : fileName;
        if (!seenNames.add(stem)) {
            LOG.warn(
                "Duplicate repo name '{}' (from '{}'), skipping", stem, fileName
            );
            return new RepoResult(
                stem, "-", -1L, -1L, "SKIPPED (duplicate repo name)"
            );
        }
        final RepoEntry entry;
        try {
            entry = RepoConfigYaml.parse(file);
        } catch (final IOException ex) {
            LOG.warn("PARSE_ERROR for '{}': {}", fileName, ex.getMessage());
            return new RepoResult(
                stem, "-", -1L, -1L,
                "PARSE_ERROR (" + ex.getMessage() + ")"
            );
        }
        final String rawType = entry.rawType();
        final Scanner scanner;
        try {
            scanner = ScannerFactory.create(rawType);
        } catch (final IllegalArgumentException ex) {
            LOG.warn(
                "Unknown type '{}' for repo '{}', skipping",
                rawType, stem
            );
            return new RepoResult(
                stem, "[UNKNOWN]", -1L, -1L,
                "SKIPPED (unknown type: " + rawType + ")"
            );
        }
        final Path storagePath = this.storageRoot.resolve(stem);
        if (!Files.exists(storagePath)) {
            LOG.warn(
                "Storage path missing for repo '{}': {}", stem, storagePath
            );
            return new RepoResult(
                stem, rawType, -1L, -1L, "SKIPPED (storage path missing)"
            );
        }
        return this.scanRepo(stem, rawType, scanner, storagePath);
    }

    /**
     * Scan one repo directory and return a result row.
     *
     * @param repoName Repo name (for logging and record insertion)
     * @param scannerType Normalised scanner type string (for display)
     * @param scanner Scanner instance
     * @param storagePath Root directory to scan
     * @return Result row
     */
    private RepoResult scanRepo(
        final String repoName,
        final String scannerType,
        final Scanner scanner,
        final Path storagePath
    ) {
        LOG.info(
            "Scanning repo '{}' (type={}) at {}",
            repoName, scannerType, storagePath
        );
        final ProgressReporter reporter =
            new ProgressReporter(this.logInterval);
        long inserted = -1L;
        long dbSkipped = -1L;
        boolean failed = false;
        String failMsg = null;
        final BatchInserter inserter = new BatchInserter(
            this.dataSource, this.batchSize, this.dryRun
        );
        try (
            inserter;
            Stream<ArtifactRecord> stream =
                scanner.scan(storagePath, repoName)
        ) {
            stream
                .map(r -> new ArtifactRecord(
                    r.repoType(), r.repoName(), r.name(),
                    r.version(), r.size(), r.createdDate(),
                    r.releaseDate(), this.owner, r.pathPrefix()
                ))
                .forEach(rec -> {
                    inserter.accept(rec);
                    reporter.increment();
                });
        } catch (final Exception ex) {
            // inserter.close() was called by try-with-resources before this catch block.
            // For FAILED rows, use -1L sentinel per design.
            failed = true;
            failMsg = ex.getMessage();
            LOG.error(
                "Scan FAILED for repo '{}': {}", repoName, ex.getMessage(), ex
            );
        }
        // inserter.close() has been called (flushed remaining batch). Read final counts.
        if (!failed) {
            inserted = inserter.getInsertedCount();
            dbSkipped = inserter.getSkippedCount();
        }
        reporter.printFinalSummary();
        if (failed) {
            return new RepoResult(
                repoName, scannerType, -1L, -1L,
                "FAILED (" + failMsg + ")"
            );
        }
        return new RepoResult(repoName, scannerType, inserted, dbSkipped, "OK");
    }

    /**
     * Print the summary table to the output stream.
     *
     * @param results List of result rows
     */
    private void printSummary(final List<RepoResult> results) {
        this.out.printf(
            "%nBulk backfill complete — %d repos processed%n",
            results.size()
        );
        for (final RepoResult row : results) {
            final String counts;
            if (row.inserted() < 0) {
                counts = String.format("%-30s", "-");
            } else {
                counts = String.format(
                    "inserted=%-10d skipped=%-6d",
                    row.inserted(), row.dbSkipped()
                );
            }
            this.out.printf(
                "  %-20s [%-12s] %s %s%n",
                row.repoName(), row.displayType(), counts, row.status()
            );
        }
        final long failCount = results.stream()
            .filter(r -> r.status().startsWith("FAILED")).count();
        if (failCount > 0) {
            this.out.printf("%nExit code: 1  (%d repo(s) failed)%n", failCount);
        } else {
            this.out.println("\nExit code: 0");
        }
    }

    /**
     * One row in the bulk run summary.
     *
     * @param repoName Repo name
     * @param displayType Type string for display
     * @param inserted Records inserted (or -1 if not applicable)
     * @param dbSkipped Records skipped at DB level (or -1 if not applicable)
     * @param status Status string
     */
    private record RepoResult(
        String repoName,
        String displayType,
        long inserted,
        long dbSkipped,
        String status
    ) {
    }
}
