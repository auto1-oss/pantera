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
package com.auto1.pantera.rpm;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.fs.FileStorage;
import java.nio.file.Path;

/**
 * Cli tool main class.
 *
 * @since 0.6
 */
public final class Cli {

    /**
     * Rpm tool.
     */
    private final Rpm rpm;

    /**
     * Ctor.
     * @param rpm Rpm instance
     */
    private Cli(final Rpm rpm) {
        this.rpm = rpm;
    }

    /**
     * Main method of Cli tool.
     *
     * @param args Arguments of command line
     */
    public static void main(final String... args) {
        final CliArguments cliargs = new CliArguments(args);
        final RepoConfig cnfg = cliargs.config();
        final NamingPolicy naming = cnfg.naming();
        System.out.printf("RPM naming-policy=%s\n", naming); // NOPMD SystemPrintln - CLI tool, stdout is the UI
        final Digest digest = cnfg.digest();
        System.out.printf("RPM digest=%s\n", digest); // NOPMD SystemPrintln - CLI tool, stdout is the UI
        final boolean filelists = cnfg.filelists();
        System.out.printf("RPM file-lists=%s\n", filelists); // NOPMD SystemPrintln - CLI tool, stdout is the UI
        final Path repository = cliargs.repository();
        System.out.printf("RPM repository=%s\n", repository); // NOPMD SystemPrintln - CLI tool, stdout is the UI
        try {
            new Cli(
                new Rpm(
                    new FileStorage(repository),
                    naming,
                    digest,
                    filelists
                )
            ).run();
        } catch (final Exception err) {
            System.err.printf("RPM failed: %s\n", err.getLocalizedMessage()); // NOPMD SystemPrintln - CLI tool, stdout is the UI
            err.printStackTrace(System.err);
        }
    }

    /**
     * Run CLI tool.
     */
    private void run() {
        this.rpm.batchUpdate(Key.ROOT).blockingAwait();
    }
}
