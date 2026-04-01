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
package com.auto1.pantera.backfill;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI entry point for the artifact backfill tool.
 *
 * <p>Supports three modes:</p>
 * <ul>
 *   <li><b>Single-repo:</b> {@code --type}, {@code --path}, {@code --repo-name}
 *       (original behaviour)</li>
 *   <li><b>Bulk:</b> {@code --config-dir}, {@code --storage-root} — reads all
 *       {@code *.yaml} Pantera repo configs and scans each repo automatically</li>
 *   <li><b>PyPI metadata:</b> {@code --mode pypi-metadata}, {@code --storage-root},
 *       {@code --repos} — writes {@code .pypi/metadata} sidecar JSON files for
 *       existing packages that do not yet have one</li>
 * </ul>
 *
 * @since 1.20.13
 */
public final class BackfillCli {

    /**
     * SLF4J logger.
     */
    private static final Logger LOG =
        LoggerFactory.getLogger(BackfillCli.class);

    /**
     * Default batch size for inserts.
     */
    private static final int DEFAULT_BATCH_SIZE = 1000;

    /**
     * Default progress log interval.
     */
    private static final int DEFAULT_LOG_INTERVAL = 10000;

    /**
     * Default database user.
     */
    private static final String DEFAULT_DB_USER = "pantera";

    /**
     * Default database password.
     */
    private static final String DEFAULT_DB_PASSWORD = "pantera";

    /**
     * Default owner.
     */
    private static final String DEFAULT_OWNER = "system";

    /**
     * HikariCP maximum pool size.
     */
    private static final int POOL_MAX_SIZE = 5;

    /**
     * HikariCP minimum idle connections.
     */
    private static final int POOL_MIN_IDLE = 1;

    /**
     * HikariCP connection timeout in millis.
     */
    private static final long POOL_CONN_TIMEOUT = 5000L;

    /**
     * HikariCP idle timeout in millis.
     */
    private static final long POOL_IDLE_TIMEOUT = 30000L;

    /**
     * Private ctor to prevent instantiation.
     */
    private BackfillCli() {
    }

    /**
     * CLI entry point.
     *
     * @param args Command-line arguments
     */
    public static void main(final String... args) {
        System.exit(run(args));
    }

    /**
     * Core logic extracted for testability. Returns an exit code
     * (0 = success, 1 = error).
     *
     * @param args Command-line arguments
     * @return Exit code
     */
    @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.NPathComplexity"})
    static int run(final String... args) {
        final Options options = buildOptions();
        for (final String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                printHelp(options);
                return 0;
            }
        }
        final CommandLine cmd;
        try {
            cmd = new DefaultParser().parse(options, args);
        } catch (final ParseException ex) {
            LOG.error("Failed to parse arguments: {}", ex.getMessage());
            printHelp(options);
            return 1;
        }
        final String mode = cmd.getOptionValue("mode", "");
        if ("pypi-metadata".equals(mode)) {
            return runPypiMetadata(
                cmd.getOptionValue("storage-root"),
                cmd.getOptionValue("repos"),
                cmd.hasOption("dry-run")
            );
        }
        final boolean hasBulkFlags =
            cmd.hasOption("config-dir") || cmd.hasOption("storage-root");
        final boolean hasSingleFlags =
            cmd.hasOption("type") || cmd.hasOption("path")
                || cmd.hasOption("repo-name");
        if (hasBulkFlags && hasSingleFlags) {
            LOG.error(
                "--config-dir/--storage-root cannot be combined with "
                    + "--type/--path/--repo-name"
            );
            return 1;
        }
        if (cmd.hasOption("config-dir") && !cmd.hasOption("storage-root")) {
            LOG.error("--config-dir requires --storage-root");
            return 1;
        }
        if (cmd.hasOption("storage-root") && !cmd.hasOption("config-dir")) {
            LOG.error("--storage-root requires --config-dir");
            return 1;
        }
        if (!hasBulkFlags && !hasSingleFlags) {
            LOG.error(
                "Either --type/--path/--repo-name or "
                    + "--config-dir/--storage-root must be provided"
            );
            printHelp(options);
            return 1;
        }
        final boolean dryRun = cmd.hasOption("dry-run");
        final String dbUrl = cmd.getOptionValue("db-url");
        final String dbUser = cmd.getOptionValue("db-user", DEFAULT_DB_USER);
        final String dbPassword =
            cmd.getOptionValue("db-password", DEFAULT_DB_PASSWORD);
        final int batchSize = Integer.parseInt(
            cmd.getOptionValue(
                "batch-size", String.valueOf(DEFAULT_BATCH_SIZE)
            )
        );
        final String owner = cmd.getOptionValue("owner", DEFAULT_OWNER);
        final int logInterval = Integer.parseInt(
            cmd.getOptionValue(
                "log-interval", String.valueOf(DEFAULT_LOG_INTERVAL)
            )
        );
        if (cmd.hasOption("config-dir")) {
            return runBulk(
                cmd.getOptionValue("config-dir"),
                cmd.getOptionValue("storage-root"),
                dryRun, dbUrl, dbUser, dbPassword,
                batchSize, owner, logInterval
            );
        }
        return runSingle(
            cmd.getOptionValue("type"),
            cmd.getOptionValue("path"),
            cmd.getOptionValue("repo-name"),
            dryRun, dbUrl, dbUser, dbPassword,
            batchSize, owner, logInterval
        );
    }

    /**
     * Run bulk mode: scan all repos from the config directory.
     *
     * @param configDirStr Config directory path string
     * @param storageRootStr Storage root path string
     * @param dryRun Dry run flag
     * @param dbUrl JDBC URL (may be null if dryRun)
     * @param dbUser DB user
     * @param dbPassword DB password
     * @param batchSize Batch insert size
     * @param owner Artifact owner
     * @param logInterval Progress log interval
     * @return Exit code
     * @checkstyle ParameterNumberCheck (15 lines)
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    private static int runBulk(
        final String configDirStr,
        final String storageRootStr,
        final boolean dryRun,
        final String dbUrl,
        final String dbUser,
        final String dbPassword,
        final int batchSize,
        final String owner,
        final int logInterval
    ) {
        final Path configDir = Paths.get(configDirStr);
        final Path storageRoot = Paths.get(storageRootStr);
        if (!Files.isDirectory(configDir)) {
            LOG.error("--config-dir is not a directory: {}", configDirStr);
            return 1;
        }
        if (!Files.isDirectory(storageRoot)) {
            LOG.error("--storage-root is not a directory: {}", storageRootStr);
            return 1;
        }
        if (!dryRun && (dbUrl == null || dbUrl.isEmpty())) {
            LOG.error("--db-url is required unless --dry-run is set");
            return 1;
        }
        DataSource dataSource = null;
        if (!dryRun) {
            dataSource = buildDataSource(dbUrl, dbUser, dbPassword);
        }
        try {
            return new BulkBackfillRunner(
                configDir, storageRoot, dataSource,
                owner, batchSize, dryRun, logInterval, System.err
            ).run();
        } catch (final IOException ex) {
            LOG.error("Bulk backfill failed: {}", ex.getMessage(), ex);
            return 1;
        } finally {
            closeDataSource(dataSource);
        }
    }

    /**
     * Run pypi-metadata mode: write missing sidecar JSON files for every
     * PyPI distribution in the listed repositories.
     *
     * @param storageRootStr Storage root path string
     * @param reposStr Comma-separated list of repository names
     * @param dryRun When true, log only — do not write any files
     * @return Exit code
     */
    private static int runPypiMetadata(
        final String storageRootStr,
        final String reposStr,
        final boolean dryRun
    ) {
        if (storageRootStr == null || storageRootStr.isEmpty()) {
            LOG.error(
                "--storage-root is required for --mode pypi-metadata"
            );
            return 1;
        }
        if (reposStr == null || reposStr.isEmpty()) {
            LOG.error("--repos is required for --mode pypi-metadata");
            return 1;
        }
        final Path storageRoot = Paths.get(storageRootStr);
        if (!Files.isDirectory(storageRoot)) {
            LOG.error(
                "--storage-root is not a directory: {}", storageRootStr
            );
            return 1;
        }
        final String[] repos = reposStr.split(",");
        final PypiMetadataBackfill backfill =
            new PypiMetadataBackfill(storageRoot, dryRun);
        int totalProcessed = 0;
        int totalCreated = 0;
        int totalSkipped = 0;
        for (final String repo : repos) {
            final String repoName = repo.trim();
            if (repoName.isEmpty()) {
                continue;
            }
            LOG.info(
                "PyPI metadata backfill starting: repo={}, dry-run={}",
                repoName, dryRun
            );
            try {
                final int[] stats = backfill.backfill(repoName);
                totalProcessed += stats[0];
                totalCreated += stats[1];
                totalSkipped += stats[2];
                LOG.info(
                    "PyPI metadata backfill complete: repo={} "
                        + "processed={} created={} skipped={}",
                    repoName, stats[0], stats[1], stats[2]
                );
            } catch (final IOException ex) {
                LOG.error(
                    "PyPI metadata backfill failed for repo {}: {}",
                    repoName, ex.getMessage(), ex
                );
                return 1;
            }
        }
        LOG.info(
            "PyPI metadata backfill summary: "
                + "total-processed={} total-created={} total-skipped={}",
            totalProcessed, totalCreated, totalSkipped
        );
        return 0;
    }

    /**
     * Run single-repo mode (original behaviour).
     *
     * @param type Scanner type
     * @param pathStr Path string
     * @param repoName Repo name
     * @param dryRun Dry run flag
     * @param dbUrl JDBC URL
     * @param dbUser DB user
     * @param dbPassword DB password
     * @param batchSize Batch size
     * @param owner Artifact owner
     * @param logInterval Progress interval
     * @return Exit code
     * @checkstyle ParameterNumberCheck (15 lines)
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    private static int runSingle(
        final String type,
        final String pathStr,
        final String repoName,
        final boolean dryRun,
        final String dbUrl,
        final String dbUser,
        final String dbPassword,
        final int batchSize,
        final String owner,
        final int logInterval
    ) {
        if (type == null || pathStr == null || repoName == null) {
            LOG.error(
                "--type, --path, and --repo-name are all required in single-repo mode"
            );
            return 1;
        }
        final Path root = Paths.get(pathStr);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            LOG.error(
                "Path does not exist or is not a directory: {}", pathStr
            );
            return 1;
        }
        if (!dryRun && (dbUrl == null || dbUrl.isEmpty())) {
            LOG.error("--db-url is required unless --dry-run is set");
            return 1;
        }
        final Scanner scanner;
        try {
            scanner = ScannerFactory.create(type);
        } catch (final IllegalArgumentException ex) {
            LOG.error(
                "Invalid scanner type '{}': {}", type, ex.getMessage()
            );
            return 1;
        }
        LOG.info(
            "Backfill starting: type={}, path={}, repo-name={}, "
                + "batch-size={}, dry-run={}",
            type, root, repoName, batchSize, dryRun
        );
        DataSource dataSource = null;
        if (!dryRun) {
            dataSource = buildDataSource(dbUrl, dbUser, dbPassword);
        }
        final ProgressReporter progress =
            new ProgressReporter(logInterval);
        try (BatchInserter inserter =
            new BatchInserter(dataSource, batchSize, dryRun)) {
            try (Stream<ArtifactRecord> stream =
                scanner.scan(root, repoName)) {
                stream
                    .map(rec -> new ArtifactRecord(
                        rec.repoType(), rec.repoName(), rec.name(),
                        rec.version(), rec.size(), rec.createdDate(),
                        rec.releaseDate(), owner, rec.pathPrefix()
                    ))
                    .forEach(record -> {
                        inserter.accept(record);
                        progress.increment();
                    });
            }
        } catch (final Exception ex) {
            LOG.error("Backfill failed: {}", ex.getMessage(), ex);
            return 1;
        } finally {
            closeDataSource(dataSource);
        }
        progress.printFinalSummary();
        LOG.info("Backfill completed successfully");
        return 0;
    }

    /**
     * Build a HikariCP datasource.
     *
     * @param dbUrl JDBC URL
     * @param dbUser DB user
     * @param dbPassword DB password
     * @return DataSource
     */
    private static DataSource buildDataSource(
        final String dbUrl,
        final String dbUser,
        final String dbPassword
    ) {
        final HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dbUrl);
        config.setUsername(dbUser);
        config.setPassword(dbPassword);
        config.setMaximumPoolSize(POOL_MAX_SIZE);
        config.setMinimumIdle(POOL_MIN_IDLE);
        config.setConnectionTimeout(POOL_CONN_TIMEOUT);
        config.setIdleTimeout(POOL_IDLE_TIMEOUT);
        config.setPoolName("Backfill-Pool");
        return new HikariDataSource(config);
    }

    /**
     * Close a HikariDataSource if non-null.
     *
     * @param dataSource DataSource to close (may be null)
     */
    private static void closeDataSource(final DataSource dataSource) {
        if (dataSource instanceof HikariDataSource) {
            ((HikariDataSource) dataSource).close();
        }
    }

    /**
     * Build the CLI option definitions.
     *
     * @return Options instance
     */
    private static Options buildOptions() {
        final Options options = new Options();
        options.addOption(
            Option.builder("m").longOpt("mode")
                .hasArg().argName("MODE")
                .desc("Backfill mode: pypi-metadata — write missing PyPI "
                    + "sidecar JSON files for existing packages")
                .build()
        );
        options.addOption(
            Option.builder().longOpt("repos")
                .hasArg().argName("REPOS")
                .desc("Comma-separated list of repository names "
                    + "(required for --mode pypi-metadata)")
                .build()
        );
        options.addOption(
            Option.builder("t").longOpt("type")
                .hasArg().argName("TYPE")
                .desc("Scanner type — single-repo mode (maven, docker, npm, "
                    + "pypi, go, helm, composer, file, etc.)")
                .build()
        );
        options.addOption(
            Option.builder("p").longOpt("path")
                .hasArg().argName("PATH")
                .desc("Root directory path to scan — single-repo mode")
                .build()
        );
        options.addOption(
            Option.builder("r").longOpt("repo-name")
                .hasArg().argName("NAME")
                .desc("Repository name — single-repo mode")
                .build()
        );
        options.addOption(
            Option.builder("C").longOpt("config-dir")
                .hasArg().argName("DIR")
                .desc("Directory of Pantera *.yaml repo configs — bulk mode")
                .build()
        );
        options.addOption(
            Option.builder("R").longOpt("storage-root")
                .hasArg().argName("DIR")
                .desc("Storage root; each repo lives at <root>/<repo-name>/ "
                    + "— bulk mode and pypi-metadata mode")
                .build()
        );
        options.addOption(
            Option.builder().longOpt("db-url")
                .hasArg().argName("URL")
                .desc("JDBC PostgreSQL URL (required unless --dry-run)")
                .build()
        );
        options.addOption(
            Option.builder().longOpt("db-user")
                .hasArg().argName("USER")
                .desc("Database user (default: pantera)")
                .build()
        );
        options.addOption(
            Option.builder().longOpt("db-password")
                .hasArg().argName("PASS")
                .desc("Database password (default: pantera)")
                .build()
        );
        options.addOption(
            Option.builder("b").longOpt("batch-size")
                .hasArg().argName("SIZE")
                .desc("Batch insert size (default: 1000)")
                .build()
        );
        options.addOption(
            Option.builder().longOpt("owner")
                .hasArg().argName("OWNER")
                .desc("Default owner (default: system)")
                .build()
        );
        options.addOption(
            Option.builder().longOpt("log-interval")
                .hasArg().argName("N")
                .desc("Progress log interval (default: 10000)")
                .build()
        );
        options.addOption(
            Option.builder().longOpt("dry-run")
                .desc("Scan only, do not write to database")
                .build()
        );
        options.addOption(
            Option.builder("h").longOpt("help")
                .desc("Print help and exit")
                .build()
        );
        return options;
    }

    /**
     * Print usage help to stdout.
     *
     * @param options CLI options
     */
    private static void printHelp(final Options options) {
        new HelpFormatter().printHelp(
            "backfill-cli",
            "Backfill the PostgreSQL artifacts table from disk storage, "
                + "or generate PyPI sidecar metadata files",
            options,
            "",
            true
        );
    }
}
