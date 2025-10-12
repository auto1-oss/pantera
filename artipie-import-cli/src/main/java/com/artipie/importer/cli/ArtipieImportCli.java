/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer.cli;

import com.artipie.importer.api.ChecksumPolicy;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class ArtipieImportCli {

    private ArtipieImportCli() {
    }

    public static void main(final String[] args) {
        if (args.length == 0 || hasFlag(args, "help")) {
            usage();
            return;
        }
        final Map<String, String> values = parse(args);
        if (!values.containsKey("url") || !values.containsKey("export-dir")) {
            System.err.println("Missing required options --url and --export-dir");
            usage();
            System.exit(1);
        }
        final ImporterConfig config = new ImporterConfig(
            URI.create(values.get("url")),
            values.get("username"),
            values.get("password"),
            values.get("token"),
            Path.of(values.get("export-dir")),
            Integer.parseInt(values.getOrDefault("concurrency", "4")),
            ChecksumPolicy.fromHeader(values.getOrDefault("checksum-mode", "compute")),
            Path.of(values.getOrDefault("progress-log", "import-progress.log")),
            Path.of(values.getOrDefault("failures-dir", "import-failures")),
            values.containsKey("resume"),
            values.containsKey("retry"),
            values.containsKey("dry-run"),
            values.getOrDefault("owner", "UNKNOWN"),
            Integer.parseInt(values.getOrDefault("max-retries", "5")),
            Long.parseLong(values.getOrDefault("backoff-ms", "500")),
            Path.of(values.getOrDefault("report", "import-report.json"))
        );
        final int code = new ImporterRunner(config).run();
        if (code != 0) {
            System.exit(code);
        }
    }

    private static Map<String, String> parse(final String[] args) {
        final Map<String, String> values = new HashMap<>();
        final Set<String> flags = Set.of("resume", "retry", "dry-run", "help");
        for (int idx = 0; idx < args.length; idx += 1) {
            final String arg = args[idx];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }
            final String raw = arg.substring(2);
            final int eq = raw.indexOf('=');
            if (eq > 0) {
                final String key = raw.substring(0, eq);
                final String value = raw.substring(eq + 1);
                values.put(key, value);
            } else if (flags.contains(raw)) {
                values.put(raw, "true");
            } else {
                if (idx + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for " + raw);
                }
                values.put(raw, args[++idx]);
            }
        }
        return values;
    }

    private static boolean hasFlag(final String[] args, final String flag) {
        final String needle = "--" + flag;
        for (final String arg : args) {
            if (arg.equals(needle)) {
                return true;
            }
        }
        return false;
    }

    private static void usage() {
        System.out.println("Usage: artipie-import --url <server> --export-dir <path> [options]\n" +
            "  --username <user>           Basic auth username\n" +
            "  --password <pass>           Basic auth password\n" +
            "  --token <token>             Bearer token\n" +
            "  --concurrency <n>           Parallel uploads (default 4)\n" +
            "  --checksum-mode <mode>      compute | metadata | skip (default compute)\n" +
            "  --progress-log <file>       Progress log path\n" +
            "  --failures-dir <dir>        Directory for per-repo failure lists\n" +
            "  --resume                    Resume from progress log\n" +
            "  --retry                     Retry only items from failures-dir\n" +
            "  --dry-run                   Enumerate artifacts only\n" +
            "  --owner <name>              Override artifact owner\n" +
            "  --max-retries <n>           Max retries per upload (default 5)\n" +
            "  --backoff-ms <ms>           Initial retry backoff (default 500)\n" +
            "  --report <file>             JSON summary output (default import-report.json)\n" +
            "  --help                      Show this message");
    }
}
