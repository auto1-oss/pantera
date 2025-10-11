/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer.cli;

import com.artipie.importer.api.ChecksumPolicy;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

final class ImporterConfig {

    private final URI server;
    private final String username;
    private final String password;
    private final String token;
    private final Path exportDir;
    private final int concurrency;
    private final ChecksumPolicy checksumPolicy;
    private final Path progressLog;
    private final Path failuresDir;
    private final boolean resume;
    private final boolean dryRun;
    private final String owner;
    private final int maxRetries;
    private final long backoffMs;
    private final Path report;

    ImporterConfig(
        final URI server,
        final String username,
        final String password,
        final String token,
        final Path exportDir,
        final int concurrency,
        final ChecksumPolicy checksumPolicy,
        final Path progressLog,
        final Path failuresDir,
        final boolean resume,
        final boolean dryRun,
        final String owner,
        final int maxRetries,
        final long backoffMs,
        final Path report
    ) {
        this.server = Objects.requireNonNull(server);
        this.username = username;
        this.password = password;
        this.token = token;
        this.exportDir = Objects.requireNonNull(exportDir);
        this.concurrency = Math.max(1, concurrency);
        this.checksumPolicy = Objects.requireNonNull(checksumPolicy);
        this.progressLog = Objects.requireNonNull(progressLog);
        this.failuresDir = Objects.requireNonNull(failuresDir);
        this.resume = resume;
        this.dryRun = dryRun;
        this.owner = owner == null || owner.isBlank() ? "UNKNOWN" : owner;
        this.maxRetries = Math.max(1, maxRetries);
        this.backoffMs = Math.max(100L, backoffMs);
        this.report = Objects.requireNonNull(report);
    }

    URI server() {
        return this.server;
    }

    String username() {
        return this.username;
    }

    String password() {
        return this.password;
    }

    String token() {
        return this.token;
    }

    Path exportDir() {
        return this.exportDir;
    }

    int concurrency() {
        return this.concurrency;
    }

    ChecksumPolicy checksumPolicy() {
        return this.checksumPolicy;
    }

    Path progressLog() {
        return this.progressLog;
    }

    Path failuresDir() {
        return this.failuresDir;
    }

    boolean resume() {
        return this.resume;
    }

    boolean dryRun() {
        return this.dryRun;
    }

    String owner() {
        return this.owner;
    }

    int maxRetries() {
        return this.maxRetries;
    }

    long backoffMs() {
        return this.backoffMs;
    }

    Path report() {
        return this.report;
    }
}
