/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.backfill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link GoScanner}.
 *
 * @since 1.20.13
 */
final class GoScannerTest {

    @Test
    void scansModuleWithVersions(@TempDir final Path temp) throws IOException {
        final Path atv = temp.resolve("example.com/foo/bar/@v");
        Files.createDirectories(atv);
        Files.writeString(
            atv.resolve("list"),
            "v1.0.0\nv1.1.0\n",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            atv.resolve("v1.0.0.info"),
            "{\"Version\":\"v1.0.0\",\"Time\":\"2024-01-15T10:30:00Z\"}",
            StandardCharsets.UTF_8
        );
        Files.write(atv.resolve("v1.0.0.zip"), new byte[200]);
        Files.writeString(
            atv.resolve("v1.1.0.info"),
            "{\"Version\":\"v1.1.0\",\"Time\":\"2024-02-20T14:00:00Z\"}",
            StandardCharsets.UTF_8
        );
        Files.write(atv.resolve("v1.1.0.zip"), new byte[350]);
        final GoScanner scanner = new GoScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "go-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 2 records for 2 versions",
            records,
            Matchers.hasSize(2)
        );
        MatcherAssert.assertThat(
            "All records should have module path example.com/foo/bar",
            records.stream().allMatch(
                r -> "example.com/foo/bar".equals(r.name())
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "All records should have repoType go",
            records.stream().allMatch(r -> "go".equals(r.repoType())),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain version 1.0.0",
            records.stream().anyMatch(r -> "1.0.0".equals(r.version())),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain version 1.1.0",
            records.stream().anyMatch(r -> "1.1.0".equals(r.version())),
            Matchers.is(true)
        );
        final ArtifactRecord first = records.stream()
            .filter(r -> "1.0.0".equals(r.version()))
            .findFirst()
            .orElseThrow();
        MatcherAssert.assertThat(
            "v1.0.0 zip size should be 200",
            first.size(),
            Matchers.is(200L)
        );
        final ArtifactRecord second = records.stream()
            .filter(r -> "1.1.0".equals(r.version()))
            .findFirst()
            .orElseThrow();
        MatcherAssert.assertThat(
            "v1.1.0 zip size should be 350",
            second.size(),
            Matchers.is(350L)
        );
    }

    @Test
    void handlesMissingZipFile(@TempDir final Path temp) throws IOException {
        final Path atv = temp.resolve("example.com/lib/@v");
        Files.createDirectories(atv);
        Files.writeString(
            atv.resolve("list"),
            "v2.0.0\n",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            atv.resolve("v2.0.0.info"),
            "{\"Version\":\"v2.0.0\",\"Time\":\"2024-03-10T08:00:00Z\"}",
            StandardCharsets.UTF_8
        );
        final GoScanner scanner = new GoScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "go-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 0 records when zip is not cached",
            records,
            Matchers.empty()
        );
    }

    @Test
    void handlesMissingInfoFile(@TempDir final Path temp) throws IOException {
        final Path atv = temp.resolve("example.com/noinfo/@v");
        Files.createDirectories(atv);
        Files.writeString(
            atv.resolve("list"),
            "v3.0.0\n",
            StandardCharsets.UTF_8
        );
        Files.write(atv.resolve("v3.0.0.zip"), new byte[100]);
        final GoScanner scanner = new GoScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "go-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should still produce 1 record",
            records,
            Matchers.hasSize(1)
        );
        final long listMtime = Files.readAttributes(
            atv.resolve("list"), BasicFileAttributes.class
        ).lastModifiedTime().toMillis();
        MatcherAssert.assertThat(
            "CreatedDate should fall back to list file mtime",
            records.get(0).createdDate(),
            Matchers.is(listMtime)
        );
    }

    @Test
    void parsesTimestampFromInfoFile(@TempDir final Path temp)
        throws IOException {
        final String timestamp = "2024-01-15T10:30:00Z";
        final long expected = Instant.parse(timestamp).toEpochMilli();
        final Path atv = temp.resolve("example.com/timed/@v");
        Files.createDirectories(atv);
        Files.writeString(
            atv.resolve("list"),
            "v1.0.0\n",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            atv.resolve("v1.0.0.info"),
            "{\"Version\":\"v1.0.0\",\"Time\":\"" + timestamp + "\"}",
            StandardCharsets.UTF_8
        );
        Files.write(atv.resolve("v1.0.0.zip"), new byte[50]);
        final GoScanner scanner = new GoScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "go-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 1 record",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "CreatedDate should match the parsed Time field",
            records.get(0).createdDate(),
            Matchers.is(expected)
        );
    }

    @Test
    void skipsUncachedVersionsInListFile(@TempDir final Path temp)
        throws IOException {
        // List has v1.0.1–v1.0.4 but only v1.0.4 was actually downloaded
        final Path atv = temp.resolve("gopkg.in/example/@v");
        Files.createDirectories(atv);
        Files.writeString(
            atv.resolve("list"),
            "v1.0.1\nv1.0.2\nv1.0.3\nv1.0.4\n",
            StandardCharsets.UTF_8
        );
        for (final String ver : new String[]{"v1.0.1", "v1.0.2", "v1.0.3", "v1.0.4"}) {
            Files.writeString(
                atv.resolve(ver + ".info"),
                "{\"Version\":\"" + ver + "\",\"Time\":\"2024-01-01T00:00:00Z\"}",
                StandardCharsets.UTF_8
            );
        }
        // Only v1.0.4 has a zip (actually cached)
        Files.write(atv.resolve("v1.0.4.zip"), new byte[12345]);
        final GoScanner scanner = new GoScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "go-proxy")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should only index the one version that has a zip",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Only cached version 1.0.4 should be indexed",
            records.get(0).version(),
            Matchers.is("1.0.4")
        );
        MatcherAssert.assertThat(
            "Size should reflect the zip file",
            records.get(0).size(),
            Matchers.is(12345L)
        );
    }

    @Test
    void handlesEmptyListFile(@TempDir final Path temp) throws IOException {
        final Path atv = temp.resolve("example.com/empty/@v");
        Files.createDirectories(atv);
        Files.writeString(
            atv.resolve("list"),
            "",
            StandardCharsets.UTF_8
        );
        final GoScanner scanner = new GoScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "go-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Empty list file should produce 0 records",
            records,
            Matchers.empty()
        );
    }

    @Test
    void scansByInfoFilesWhenNoListFile(@TempDir final Path temp)
        throws IOException {
        // Proxy layout: only .info and .zip files, no list file
        final Path atv = temp.resolve("example.com/proxy-mod/@v");
        Files.createDirectories(atv);
        Files.writeString(
            atv.resolve("v1.0.0.info"),
            "{\"Version\":\"v1.0.0\",\"Time\":\"2024-06-01T12:00:00Z\"}",
            StandardCharsets.UTF_8
        );
        Files.write(atv.resolve("v1.0.0.zip"), new byte[300]);
        Files.writeString(
            atv.resolve("v1.1.0.info"),
            "{\"Version\":\"v1.1.0\",\"Time\":\"2024-07-01T12:00:00Z\"}",
            StandardCharsets.UTF_8
        );
        Files.write(atv.resolve("v1.1.0.zip"), new byte[400]);
        final GoScanner scanner = new GoScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "go-proxy")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should find 2 versions via .info files",
            records,
            Matchers.hasSize(2)
        );
        MatcherAssert.assertThat(
            "All records should have module path example.com/proxy-mod",
            records.stream().allMatch(
                r -> "example.com/proxy-mod".equals(r.name())
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain version 1.0.0",
            records.stream().anyMatch(r -> "1.0.0".equals(r.version())),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain version 1.1.0",
            records.stream().anyMatch(r -> "1.1.0".equals(r.version())),
            Matchers.is(true)
        );
    }

    @Test
    void handlesNestedModulePaths(@TempDir final Path temp)
        throws IOException {
        final Path atv = temp.resolve("github.com/org/project/v2/@v");
        Files.createDirectories(atv);
        Files.writeString(
            atv.resolve("list"),
            "v2.0.0\n",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            atv.resolve("v2.0.0.info"),
            "{\"Version\":\"v2.0.0\",\"Time\":\"2024-05-01T00:00:00Z\"}",
            StandardCharsets.UTF_8
        );
        Files.write(atv.resolve("v2.0.0.zip"), new byte[500]);
        final GoScanner scanner = new GoScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "go-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 1 record for nested module",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Module path should be github.com/org/project/v2",
            records.get(0).name(),
            Matchers.is("github.com/org/project/v2")
        );
        MatcherAssert.assertThat(
            "Version should be 2.0.0 (v prefix stripped)",
            records.get(0).version(),
            Matchers.is("2.0.0")
        );
        MatcherAssert.assertThat(
            "Size should be 500",
            records.get(0).size(),
            Matchers.is(500L)
        );
    }
}
