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
package com.auto1.pantera.tools;

import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.http.cache.ProxyCacheWriter;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One-off admin tool (WI-07 §9.5) that scans a proxy cache directory for
 * primary/sidecar drift — the production {@code oss-parent-58.pom.sha1}
 * symptom — and optionally evicts mismatched pairs so the next client
 * request repopulates them through {@link ProxyCacheWriter}.
 *
 * <p>CLI contract:
 * <pre>
 *   pantera-cache-integrity-audit --root &lt;storage-dir&gt;
 *       [--repo &lt;name&gt;]       # repository tag for log events
 *       [--dry-run]              # default: report only
 *       [--fix]                  # delete mismatched primary + every sidecar
 *       [--verbose]              # print every scanned entry, not just offenders
 * </pre>
 *
 * <p>Exit codes:
 * <ul>
 *   <li>{@code 0} — no mismatches found (or all evicted after {@code --fix}).</li>
 *   <li>{@code 1} — at least one mismatch remains after the run.</li>
 *   <li>{@code 2} — CLI usage error.</li>
 * </ul>
 *
 * @since 2.2.0
 */
public final class CacheIntegrityAudit {

    /** Default tag when {@code --repo} is omitted. */
    private static final String DEFAULT_REPO = "cache-integrity-audit";

    private CacheIntegrityAudit() {
        // static main only
    }

    /**
     * CLI entry point. Declared on {@code pantera-main} jar's manifest
     * so {@code java -cp pantera-main.jar com.auto1.pantera.tools.CacheIntegrityAudit ...}
     * invokes this method directly.
     *
     * @param args CLI args per class javadoc.
     */
    public static void main(final String[] args) {
        final Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (final IllegalArgumentException ex) {
            System.err.println("error: " + ex.getMessage()); // NOPMD SystemPrintln - CLI tool, stdout is the UI
            System.err.println(); // NOPMD SystemPrintln - CLI tool, stdout is the UI
            printUsage(System.err);
            System.exit(2);
            return;
        }
        if (parsed.help) {
            printUsage(System.out);
            System.exit(0);
            return;
        }
        final Path root = Paths.get(parsed.root).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("error: --root does not exist or is not a directory: " + root); // NOPMD SystemPrintln - CLI tool, stdout is the UI
            System.exit(2);
            return;
        }
        final String repoTag = parsed.repo == null ? DEFAULT_REPO : parsed.repo;
        System.out.println("Pantera cache integrity audit"); // NOPMD SystemPrintln - CLI tool, stdout is the UI
        System.out.println("  root:    " + root); // NOPMD SystemPrintln - CLI tool, stdout is the UI
        System.out.println("  repo:    " + repoTag); // NOPMD SystemPrintln - CLI tool, stdout is the UI
        System.out.println("  mode:    " + (parsed.fix ? "fix (evict mismatches)" : "dry-run")); // NOPMD SystemPrintln - CLI tool, stdout is the UI
        System.out.println(); // NOPMD SystemPrintln - CLI tool, stdout is the UI
        final ProxyCacheWriter.IntegrityAuditor.Report report =
            ProxyCacheWriter.IntegrityAuditor.run(new FileStorage(root), repoTag, parsed.fix);
        System.out.println(); // NOPMD SystemPrintln - CLI tool, stdout is the UI
        System.out.println("Scanned primaries: " + report.scanned()); // NOPMD SystemPrintln - CLI tool, stdout is the UI
        System.out.println("Mismatches found:  " + report.mismatches().size()); // NOPMD SystemPrintln - CLI tool, stdout is the UI
        if (!report.mismatches().isEmpty()) {
            System.out.println(); // NOPMD SystemPrintln - CLI tool, stdout is the UI
            System.out.println("Offenders:"); // NOPMD SystemPrintln - CLI tool, stdout is the UI
            for (final ProxyCacheWriter.IntegrityAuditor.Mismatch m : report.mismatches()) {
                System.out.println("  " + m.primary().string()); // NOPMD SystemPrintln - CLI tool, stdout is the UI
                for (final ProxyCacheWriter.IntegrityAuditor.AlgoMismatch am : m.algorithms()) {
                    System.out.println(String.format( // NOPMD SystemPrintln - CLI tool, stdout is the UI
                        Locale.ROOT,
                        "    %-6s  cached=%s  computed=%s",
                        am.algo().name().toLowerCase(Locale.ROOT),
                        am.sidecarClaim(),
                        am.computed()
                    ));
                }
            }
        }
        if (report.clean()) {
            System.out.println(); // NOPMD SystemPrintln - CLI tool, stdout is the UI
            System.out.println("Result: CLEAN"); // NOPMD SystemPrintln - CLI tool, stdout is the UI
            System.exit(0);
            return;
        }
        if (parsed.fix) {
            System.out.println(); // NOPMD SystemPrintln - CLI tool, stdout is the UI
            System.out.println("Result: " + report.mismatches().size() // NOPMD SystemPrintln - CLI tool, stdout is the UI
                + " mismatched pair(s) evicted. "
                + "Next client request will repopulate through ProxyCacheWriter.");
            System.exit(0);
            return;
        }
        System.out.println(); // NOPMD SystemPrintln - CLI tool, stdout is the UI
        System.out.println("Result: " + report.mismatches().size() // NOPMD SystemPrintln - CLI tool, stdout is the UI
            + " mismatched pair(s) detected. Re-run with --fix to evict.");
        System.exit(1);
    }

    /** Print the usage string to {@code out}. */
    private static void printUsage(final PrintStream out) {
        out.println("Usage: pantera-cache-integrity-audit --root <storage-dir> "
            + "[--repo <name>] [--dry-run | --fix] [--verbose]");
        out.println();
        out.println("  --root <dir>   File-storage root directory (required).");
        out.println("  --repo <name>  Log/metric repository tag. Default: "
            + DEFAULT_REPO + ".");
        out.println("  --dry-run      Report only (default).");
        out.println("  --fix          Evict primary + every sidecar on mismatch.");
        out.println("  --verbose      Print every scanned entry.");
        out.println("  -h, --help     Show this help text.");
        out.println();
        out.println("Exit codes:");
        out.println("  0 = clean (or fix succeeded)");
        out.println("  1 = mismatches detected in dry-run");
        out.println("  2 = CLI usage error");
    }

    /** Parsed CLI arguments. */
    private static final class Args {
        private String root;
        private String repo;
        private boolean fix;
        private boolean help;
        @SuppressWarnings("unused")
        private boolean verbose;

        static Args parse(final String[] args) {
            final Args out = new Args();
            final List<String> rest = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                final String arg = args[i];
                switch (arg) {
                    case "-h":
                    case "--help":
                        out.help = true;
                        break;
                    case "--dry-run":
                        out.fix = false;
                        break;
                    case "--fix":
                        out.fix = true;
                        break;
                    case "--verbose":
                        out.verbose = true;
                        break;
                    case "--root":
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("--root requires a value");
                        }
                        out.root = args[++i];
                        break;
                    case "--repo":
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("--repo requires a value");
                        }
                        out.repo = args[++i];
                        break;
                    default:
                        rest.add(arg);
                        break;
                }
            }
            if (!out.help && (out.root == null || out.root.isBlank())) {
                throw new IllegalArgumentException("--root is required");
            }
            if (!rest.isEmpty()) {
                throw new IllegalArgumentException("unknown argument(s): " + rest);
            }
            return out;
        }
    }
}
