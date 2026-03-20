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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link HelmScanner}.
 *
 * @since 1.20.13
 */
final class HelmScannerTest {

    @Test
    void scansMultipleChartsWithVersions(@TempDir final Path temp)
        throws IOException {
        Files.writeString(
            temp.resolve("index.yaml"),
            String.join(
                "\n",
                "apiVersion: v1",
                "entries:",
                "  tomcat:",
                "    - name: tomcat",
                "      version: 0.4.1",
                "      urls:",
                "        - tomcat-0.4.1.tgz",
                "      created: '2021-01-11T16:21:01.376598500+03:00'",
                "  redis:",
                "    - name: redis",
                "      version: 7.0.0",
                "      urls:",
                "        - redis-7.0.0.tgz",
                "      created: '2023-05-01T10:00:00+00:00'",
                "    - name: redis",
                "      version: 6.2.0",
                "      urls:",
                "        - redis-6.2.0.tgz",
                "      created: '2022-03-15T08:30:00+00:00'"
            ),
            StandardCharsets.UTF_8
        );
        Files.write(temp.resolve("tomcat-0.4.1.tgz"), new byte[1024]);
        Files.write(temp.resolve("redis-7.0.0.tgz"), new byte[2048]);
        Files.write(temp.resolve("redis-6.2.0.tgz"), new byte[512]);
        final HelmScanner scanner = new HelmScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "helm-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 3 records total",
            records,
            Matchers.hasSize(3)
        );
        MatcherAssert.assertThat(
            "Should contain tomcat 0.4.1",
            records.stream().anyMatch(
                r -> "tomcat".equals(r.name()) && "0.4.1".equals(r.version())
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain redis 7.0.0",
            records.stream().anyMatch(
                r -> "redis".equals(r.name()) && "7.0.0".equals(r.version())
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain redis 6.2.0",
            records.stream().anyMatch(
                r -> "redis".equals(r.name()) && "6.2.0".equals(r.version())
            ),
            Matchers.is(true)
        );
        final ArtifactRecord tomcat = records.stream()
            .filter(r -> "tomcat".equals(r.name()))
            .findFirst().orElseThrow();
        MatcherAssert.assertThat(
            "Tomcat size should be 1024",
            tomcat.size(),
            Matchers.is(1024L)
        );
        final ArtifactRecord redis7 = records.stream()
            .filter(r -> "7.0.0".equals(r.version()))
            .findFirst().orElseThrow();
        MatcherAssert.assertThat(
            "Redis 7.0.0 size should be 2048",
            redis7.size(),
            Matchers.is(2048L)
        );
        final ArtifactRecord redis6 = records.stream()
            .filter(r -> "6.2.0".equals(r.version()))
            .findFirst().orElseThrow();
        MatcherAssert.assertThat(
            "Redis 6.2.0 size should be 512",
            redis6.size(),
            Matchers.is(512L)
        );
        MatcherAssert.assertThat(
            "Repo type should be helm",
            tomcat.repoType(),
            Matchers.is("helm")
        );
    }

    @Test
    void handlesMissingTgzFile(@TempDir final Path temp)
        throws IOException {
        Files.writeString(
            temp.resolve("index.yaml"),
            String.join(
                "\n",
                "apiVersion: v1",
                "entries:",
                "  nginx:",
                "    - name: nginx",
                "      version: 1.0.0",
                "      urls:",
                "        - nginx-1.0.0.tgz",
                "      created: '2023-01-01T00:00:00+00:00'"
            ),
            StandardCharsets.UTF_8
        );
        final HelmScanner scanner = new HelmScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "helm-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should still produce 1 record",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Size should be 0 when tgz is missing",
            records.get(0).size(),
            Matchers.is(0L)
        );
    }

    @Test
    void handlesMissingIndexYaml(@TempDir final Path temp)
        throws IOException {
        final HelmScanner scanner = new HelmScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "helm-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 0 records when index.yaml is missing",
            records,
            Matchers.empty()
        );
    }

    @Test
    void handlesMissingEntriesKey(@TempDir final Path temp)
        throws IOException {
        Files.writeString(
            temp.resolve("index.yaml"),
            "apiVersion: v1\n",
            StandardCharsets.UTF_8
        );
        final HelmScanner scanner = new HelmScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "helm-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 0 records when entries is missing",
            records,
            Matchers.empty()
        );
    }

    @Test
    void parsesCreatedTimestamp(@TempDir final Path temp)
        throws IOException {
        final String timestamp = "2021-01-11T16:21:01.376598500+03:00";
        final long expected = OffsetDateTime.parse(
            timestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME
        ).toInstant().toEpochMilli();
        Files.writeString(
            temp.resolve("index.yaml"),
            String.join(
                "\n",
                "apiVersion: v1",
                "entries:",
                "  mychart:",
                "    - name: mychart",
                "      version: 1.0.0",
                "      urls:",
                "        - mychart-1.0.0.tgz",
                "      created: '" + timestamp + "'"
            ),
            StandardCharsets.UTF_8
        );
        final HelmScanner scanner = new HelmScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "helm-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 1 record",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "CreatedDate should match the parsed timestamp",
            records.get(0).createdDate(),
            Matchers.is(expected)
        );
    }
}
