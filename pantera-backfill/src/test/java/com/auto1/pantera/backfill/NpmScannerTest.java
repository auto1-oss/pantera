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
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link NpmScanner}.
 *
 * @since 1.20.13
 */
final class NpmScannerTest {

    @Test
    void scansUnscopedPackageWithVersionsDir(@TempDir final Path temp)
        throws IOException {
        final Path pkg = temp.resolve("simple-modal-window");
        final Path versions = pkg.resolve(".versions");
        final Path tgzDir = pkg.resolve("-/@platform");
        Files.createDirectories(versions);
        Files.createDirectories(tgzDir);
        Files.writeString(
            versions.resolve("0.0.2.json"), "{}", StandardCharsets.UTF_8
        );
        Files.writeString(
            versions.resolve("0.0.3.json"), "{}", StandardCharsets.UTF_8
        );
        Files.write(
            tgzDir.resolve("simple-modal-window-0.0.2.tgz"),
            new byte[100]
        );
        Files.write(
            tgzDir.resolve("simple-modal-window-0.0.3.tgz"),
            new byte[200]
        );
        final NpmScanner scanner = new NpmScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "npm-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 2 records",
            records,
            Matchers.hasSize(2)
        );
        MatcherAssert.assertThat(
            "All records should have name simple-modal-window",
            records.stream().allMatch(
                r -> "simple-modal-window".equals(r.name())
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain version 0.0.2",
            records.stream().anyMatch(
                r -> "0.0.2".equals(r.version())
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain version 0.0.3",
            records.stream().anyMatch(
                r -> "0.0.3".equals(r.version())
            ),
            Matchers.is(true)
        );
        final ArtifactRecord v2 = records.stream()
            .filter(r -> "0.0.2".equals(r.version()))
            .findFirst().orElseThrow();
        MatcherAssert.assertThat(
            "Size of 0.0.2 should be 100",
            v2.size(),
            Matchers.is(100L)
        );
        final ArtifactRecord v3 = records.stream()
            .filter(r -> "0.0.3".equals(r.version()))
            .findFirst().orElseThrow();
        MatcherAssert.assertThat(
            "Size of 0.0.3 should be 200",
            v3.size(),
            Matchers.is(200L)
        );
        MatcherAssert.assertThat(
            "Repo type should be npm",
            v2.repoType(),
            Matchers.is("npm")
        );
    }

    @Test
    void scansScopedPackageWithVersionsDir(@TempDir final Path temp)
        throws IOException {
        final Path pkg = temp.resolve("@ui-components/button");
        final Path versions = pkg.resolve(".versions");
        final Path tgzDir = pkg.resolve("-/@ui-components");
        Files.createDirectories(versions);
        Files.createDirectories(tgzDir);
        Files.writeString(
            versions.resolve("0.1.8.json"), "{}", StandardCharsets.UTF_8
        );
        Files.write(
            tgzDir.resolve("button-0.1.8.tgz"), new byte[50]
        );
        final NpmScanner scanner = new NpmScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "npm-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 1 record for scoped package",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Name should be @ui-components/button",
            records.get(0).name(),
            Matchers.is("@ui-components/button")
        );
        MatcherAssert.assertThat(
            "Version should be 0.1.8",
            records.get(0).version(),
            Matchers.is("0.1.8")
        );
        MatcherAssert.assertThat(
            "Size should be 50",
            records.get(0).size(),
            Matchers.is(50L)
        );
    }

    @Test
    void handlesPreReleaseVersions(@TempDir final Path temp)
        throws IOException {
        final Path pkg = temp.resolve("ssu-popup");
        final Path versions = pkg.resolve(".versions");
        final Path tgzDir = pkg.resolve("-/@platform");
        Files.createDirectories(versions);
        Files.createDirectories(tgzDir);
        Files.writeString(
            versions.resolve("0.0.1-dev.0.json"), "{}",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            versions.resolve("0.0.1.json"), "{}", StandardCharsets.UTF_8
        );
        Files.writeString(
            versions.resolve("1.0.1-dev.2.json"), "{}",
            StandardCharsets.UTF_8
        );
        Files.write(
            tgzDir.resolve("ssu-popup-0.0.1-dev.0.tgz"), new byte[30]
        );
        Files.write(
            tgzDir.resolve("ssu-popup-0.0.1.tgz"), new byte[40]
        );
        Files.write(
            tgzDir.resolve("ssu-popup-1.0.1-dev.2.tgz"), new byte[60]
        );
        final NpmScanner scanner = new NpmScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "npm-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 3 records (including pre-release)",
            records,
            Matchers.hasSize(3)
        );
        MatcherAssert.assertThat(
            "Should contain 0.0.1-dev.0",
            records.stream().anyMatch(
                r -> "0.0.1-dev.0".equals(r.version())
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain 1.0.1-dev.2",
            records.stream().anyMatch(
                r -> "1.0.1-dev.2".equals(r.version())
            ),
            Matchers.is(true)
        );
    }

    @Test
    void handlesMultiplePackages(@TempDir final Path temp)
        throws IOException {
        final Path pkg1 = temp.resolve("tracking");
        final Path pkg2 = temp.resolve("str-formatter");
        Files.createDirectories(pkg1.resolve(".versions"));
        Files.createDirectories(pkg1.resolve("-/@platform"));
        Files.createDirectories(pkg2.resolve(".versions"));
        Files.createDirectories(pkg2.resolve("-/@platform"));
        Files.writeString(
            pkg1.resolve(".versions/0.0.1.json"), "{}",
            StandardCharsets.UTF_8
        );
        Files.write(
            pkg1.resolve("-/@platform/tracking-0.0.1.tgz"), new byte[80]
        );
        Files.writeString(
            pkg2.resolve(".versions/0.0.2.json"), "{}",
            StandardCharsets.UTF_8
        );
        Files.write(
            pkg2.resolve("-/@platform/str-formatter-0.0.2.tgz"),
            new byte[90]
        );
        final NpmScanner scanner = new NpmScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "npm-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should find records from both packages",
            records,
            Matchers.hasSize(2)
        );
        MatcherAssert.assertThat(
            "Should contain tracking",
            records.stream().anyMatch(
                r -> "tracking".equals(r.name())
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain str-formatter",
            records.stream().anyMatch(
                r -> "str-formatter".equals(r.name())
            ),
            Matchers.is(true)
        );
    }

    @Test
    void handlesMissingTgzInVersionsMode(@TempDir final Path temp)
        throws IOException {
        final Path pkg = temp.resolve("no-tgz");
        Files.createDirectories(pkg.resolve(".versions"));
        Files.writeString(
            pkg.resolve(".versions/1.0.0.json"), "{}",
            StandardCharsets.UTF_8
        );
        final NpmScanner scanner = new NpmScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "npm-repo")
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
    void fallsBackToMetaJson(@TempDir final Path temp) throws IOException {
        final Path pkgDir = temp.resolve("lodash");
        final Path tgzDir = temp.resolve("lodash/-");
        Files.createDirectories(tgzDir);
        Files.write(tgzDir.resolve("lodash-4.17.21.tgz"), new byte[12345]);
        Files.writeString(
            pkgDir.resolve("meta.json"),
            String.join(
                "\n",
                "{",
                "  \"name\": \"lodash\",",
                "  \"versions\": {",
                "    \"4.17.21\": {",
                "      \"name\": \"lodash\",",
                "      \"version\": \"4.17.21\",",
                "      \"dist\": {",
                "        \"tarball\": \"/lodash/-/lodash-4.17.21.tgz\"",
                "      }",
                "    }",
                "  }",
                "}"
            ),
            StandardCharsets.UTF_8
        );
        final NpmScanner scanner = new NpmScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "npm-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 1 record via meta.json fallback",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Name should be lodash",
            records.get(0).name(),
            Matchers.is("lodash")
        );
        MatcherAssert.assertThat(
            "Version should be 4.17.21",
            records.get(0).version(),
            Matchers.is("4.17.21")
        );
        MatcherAssert.assertThat(
            "Size should reflect the tarball",
            records.get(0).size(),
            Matchers.is(12345L)
        );
    }

    @Test
    void skipsUncachedVersionsInMetaJson(@TempDir final Path temp)
        throws IOException {
        // meta.json lists 3 versions but only 1.0.11 tarball is on disk
        final Path pkgDir = temp.resolve("pako");
        final Path tgzDir = temp.resolve("pako/-");
        Files.createDirectories(tgzDir);
        Files.write(tgzDir.resolve("pako-1.0.11.tgz"), new byte[98765]);
        Files.writeString(
            pkgDir.resolve("meta.json"),
            String.join(
                "\n",
                "{",
                "  \"name\": \"pako\",",
                "  \"versions\": {",
                "    \"1.0.9\": {\"dist\":{\"tarball\":\"/pako/-/pako-1.0.9.tgz\"}},",
                "    \"1.0.10\": {\"dist\":{\"tarball\":\"/pako/-/pako-1.0.10.tgz\"}},",
                "    \"1.0.11\": {\"dist\":{\"tarball\":\"/pako/-/pako-1.0.11.tgz\"}}",
                "  }",
                "}"
            ),
            StandardCharsets.UTF_8
        );
        final NpmScanner scanner = new NpmScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "npm-proxy")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should only index the one cached version",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Only version 1.0.11 should be indexed",
            records.get(0).version(),
            Matchers.is("1.0.11")
        );
        MatcherAssert.assertThat(
            "Size should reflect the cached tarball",
            records.get(0).size(),
            Matchers.is(98765L)
        );
    }

    @Test
    void skipsMalformedMetaJson(@TempDir final Path temp)
        throws IOException {
        final Path pkgDir = temp.resolve("broken");
        Files.createDirectories(pkgDir);
        Files.writeString(
            pkgDir.resolve("meta.json"),
            "<<<not valid json>>>",
            StandardCharsets.UTF_8
        );
        final NpmScanner scanner = new NpmScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "npm-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Malformed JSON should produce 0 records",
            records,
            Matchers.empty()
        );
    }

    @Test
    void metaJsonUsesTimeField(@TempDir final Path temp)
        throws IOException {
        final String timestamp = "2023-06-15T12:30:00.000Z";
        final long expected = Instant.parse(timestamp).toEpochMilli();
        final Path pkgDir = temp.resolve("timed");
        final Path tgzDir = temp.resolve("timed/-");
        Files.createDirectories(tgzDir);
        Files.write(tgzDir.resolve("timed-1.0.0.tgz"), new byte[100]);
        Files.writeString(
            pkgDir.resolve("meta.json"),
            String.join(
                "\n",
                "{",
                "  \"name\": \"timed\",",
                "  \"versions\": {",
                "    \"1.0.0\": {",
                "      \"name\": \"timed\",",
                "      \"version\": \"1.0.0\",",
                "      \"dist\": {",
                "        \"tarball\": \"/timed/-/timed-1.0.0.tgz\"",
                "      }",
                "    }",
                "  },",
                "  \"time\": {",
                "    \"1.0.0\": \"" + timestamp + "\"",
                "  }",
                "}"
            ),
            StandardCharsets.UTF_8
        );
        final NpmScanner scanner = new NpmScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "npm-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 1 record",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "CreatedDate should match the parsed time field",
            records.get(0).createdDate(),
            Matchers.is(expected)
        );
    }

    @Test
    void scansScopedPackageWithMetaJson(@TempDir final Path temp)
        throws IOException {
        final Path pkgDir = temp.resolve("@hello/simple");
        final Path tgzDir = temp.resolve("@hello/simple/-");
        Files.createDirectories(tgzDir);
        Files.write(tgzDir.resolve("simple-1.0.1.tgz"), new byte[200]);
        Files.writeString(
            pkgDir.resolve("meta.json"),
            String.join(
                "\n",
                "{",
                "  \"name\": \"@hello/simple\",",
                "  \"versions\": {",
                "    \"1.0.1\": {",
                "      \"name\": \"@hello/simple\",",
                "      \"version\": \"1.0.1\",",
                "      \"dist\": {",
                "        \"tarball\": \"/@hello/simple/-/simple-1.0.1.tgz\"",
                "      }",
                "    }",
                "  }",
                "}"
            ),
            StandardCharsets.UTF_8
        );
        final NpmScanner scanner = new NpmScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "npm-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 1 record for scoped package via meta.json",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Name should be @hello/simple",
            records.get(0).name(),
            Matchers.is("@hello/simple")
        );
    }

    @Test
    void returnsEmptyForEmptyDirectory(@TempDir final Path temp)
        throws IOException {
        final NpmScanner scanner = new NpmScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "npm-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Empty directory should produce no records",
            records,
            Matchers.empty()
        );
    }
}
