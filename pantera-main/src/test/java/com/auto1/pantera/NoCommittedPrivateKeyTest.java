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
package com.auto1.pantera;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Regression guard: no private-key material may live in the committed
 * docker-compose fixtures. Before 2.2.9 {@code nginx/ssl/nginx.key} was a
 * real committed private key; it is now generated at container start and
 * git-ignored. This test scans the compose tree so it cannot come back.
 *
 * @since 2.2.9
 */
final class NoCommittedPrivateKeyTest {

    private static final Pattern KEY = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");

    @Test
    void composeFixturesContainNoPrivateKey() throws IOException {
        final Path root = Paths.get("docker-compose");
        if (!Files.isDirectory(root)) {
            return;
        }
        final List<String> offenders = new ArrayList<>();
        for (final Path path : NoCommittedPrivateKeyTest.tracked(root)) {
            try (java.io.InputStream in = Files.newInputStream(path)) {
                final byte[] head = in.readNBytes(4096);
                if (KEY.matcher(new String(head, StandardCharsets.ISO_8859_1)).find()) {
                    offenders.add(root.relativize(path).toString());
                }
            } catch (final IOException ignored) {
                // Unreadable fixture files cannot carry a key we could ship.
            }
        }
        MatcherAssert.assertThat(
            "private-key material must never be committed under docker-compose: " + offenders,
            offenders.isEmpty(), new IsEqual<>(true)
        );
    }

    /**
     * The COMMITTED files under {@code root}: what git tracks. Local,
     * git-ignored material (the generated JWT key pair, data dirs, .env)
     * is legitimately present on disk and is not what this guard is about.
     * Falls back to a walk that skips the known ignored directories when
     * git is unavailable.
     */
    private static List<Path> tracked(final Path root) throws IOException {
        final List<Path> files = new ArrayList<>();
        try {
            final Process git = new ProcessBuilder("git", "ls-files", "-z", root.toString())
                .redirectErrorStream(true).start();
            final byte[] out = git.getInputStream().readAllBytes();
            final boolean done = git.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (done && git.exitValue() == 0) {
                for (final String entry : new String(out, StandardCharsets.UTF_8).split("\\u0000")) {
                    if (!entry.isBlank() && Files.isRegularFile(Paths.get(entry))) {
                        files.add(Paths.get(entry));
                    }
                }
                return files;
            }
        } catch (final IOException | InterruptedException ignored) {
            // fall through to the filesystem walk
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(path -> !path.toString().contains("/keys/")
                    && !path.toString().contains("/data/")
                    && !path.getFileName().toString().startsWith(".env"))
                .forEach(files::add);
        }
        return files;
    }
}
