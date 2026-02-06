/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.backfill;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link DebianScanner}.
 *
 * @since 1.20.13
 */
final class DebianScannerTest {

    @Test
    void parsesUncompressedPackagesFile(@TempDir final Path temp)
        throws IOException {
        final Path dir = temp.resolve("dists/focal/main/binary-amd64");
        Files.createDirectories(dir);
        Files.writeString(
            dir.resolve("Packages"),
            String.join(
                "\n",
                "Package: curl",
                "Version: 7.68.0-1ubuntu2.6",
                "Architecture: amd64",
                "Size: 161672",
                "Filename: pool/main/c/curl/curl_7.68.0-1ubuntu2.6_amd64.deb",
                "",
                "Package: wget",
                "Version: 1.20.3-1ubuntu2",
                "Architecture: amd64",
                "Size: 345678",
                "Filename: pool/main/w/wget/wget_1.20.3-1ubuntu2_amd64.deb",
                ""
            )
        );
        final DebianScanner scanner = new DebianScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "deb-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce exactly 2 records",
            records,
            Matchers.hasSize(2)
        );
        MatcherAssert.assertThat(
            "First record name should be curl_amd64",
            records.stream().anyMatch(
                r -> "curl_amd64".equals(r.name())
                    && "7.68.0-1ubuntu2.6".equals(r.version())
                    && r.size() == 161672L
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Second record name should be wget_amd64",
            records.stream().anyMatch(
                r -> "wget_amd64".equals(r.name())
                    && "1.20.3-1ubuntu2".equals(r.version())
                    && r.size() == 345678L
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Repo type should be deb",
            records.get(0).repoType(),
            Matchers.is("deb")
        );
        MatcherAssert.assertThat(
            "Owner should be system",
            records.get(0).owner(),
            Matchers.is("system")
        );
    }

    @Test
    void parsesGzipCompressedPackagesFile(@TempDir final Path temp)
        throws IOException {
        final Path dir = temp.resolve("dists/focal/main/binary-amd64");
        Files.createDirectories(dir);
        final String content = String.join(
            "\n",
            "Package: nginx",
            "Version: 1.18.0-0ubuntu1",
            "Architecture: amd64",
            "Size: 543210",
            "",
            "Package: apache2",
            "Version: 2.4.41-4ubuntu3",
            "Architecture: amd64",
            "Size: 987654",
            ""
        );
        final Path gzPath = dir.resolve("Packages.gz");
        try (OutputStream fos = Files.newOutputStream(gzPath);
            GZIPOutputStream gzos = new GZIPOutputStream(fos)) {
            gzos.write(content.getBytes(StandardCharsets.UTF_8));
        }
        final DebianScanner scanner = new DebianScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "deb-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce exactly 2 records from gzip file",
            records,
            Matchers.hasSize(2)
        );
        MatcherAssert.assertThat(
            "Should contain nginx_amd64 record",
            records.stream().anyMatch(
                r -> "nginx_amd64".equals(r.name())
                    && "1.18.0-0ubuntu1".equals(r.version())
                    && r.size() == 543210L
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain apache2_amd64 record",
            records.stream().anyMatch(
                r -> "apache2_amd64".equals(r.name())
                    && "2.4.41-4ubuntu3".equals(r.version())
                    && r.size() == 987654L
            ),
            Matchers.is(true)
        );
    }

    @Test
    void defaultsSizeToZeroWhenMissing(@TempDir final Path temp)
        throws IOException {
        final Path dir = temp.resolve("dists/focal/main/binary-amd64");
        Files.createDirectories(dir);
        Files.writeString(
            dir.resolve("Packages"),
            String.join(
                "\n",
                "Package: nano",
                "Version: 4.8-1ubuntu1",
                "Architecture: amd64",
                ""
            )
        );
        final DebianScanner scanner = new DebianScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "deb-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce exactly 1 record",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Size should default to 0 when missing",
            records.get(0).size(),
            Matchers.is(0L)
        );
    }

    @Test
    void skipsStanzasMissingPackageOrVersion(@TempDir final Path temp)
        throws IOException {
        final Path dir = temp.resolve("dists/focal/main/binary-amd64");
        Files.createDirectories(dir);
        Files.writeString(
            dir.resolve("Packages"),
            String.join(
                "\n",
                "Package: valid-pkg",
                "Version: 1.0",
                "Size: 100",
                "",
                "Version: 2.0",
                "Size: 200",
                "",
                "Package: no-version",
                "Size: 300",
                "",
                "Package: another-valid",
                "Version: 3.0",
                "Size: 400",
                ""
            )
        );
        final DebianScanner scanner = new DebianScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "deb-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce exactly 2 records, skipping incomplete stanzas",
            records,
            Matchers.hasSize(2)
        );
        MatcherAssert.assertThat(
            "Should contain valid-pkg",
            records.stream().anyMatch(
                r -> "valid-pkg".equals(r.name())
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain another-valid",
            records.stream().anyMatch(
                r -> "another-valid".equals(r.name())
            ),
            Matchers.is(true)
        );
    }

    @Test
    void handlesMultipleDistributionsAndComponents(@TempDir final Path temp)
        throws IOException {
        final Path focal = temp.resolve("dists/focal/main/binary-amd64");
        Files.createDirectories(focal);
        Files.writeString(
            focal.resolve("Packages"),
            String.join(
                "\n",
                "Package: focal-pkg",
                "Version: 1.0",
                "Size: 100",
                ""
            )
        );
        final Path bionic = temp.resolve("dists/bionic/contrib/binary-i386");
        Files.createDirectories(bionic);
        Files.writeString(
            bionic.resolve("Packages"),
            String.join(
                "\n",
                "Package: bionic-pkg",
                "Version: 2.0",
                "Size: 200",
                ""
            )
        );
        final DebianScanner scanner = new DebianScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "deb-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce records from both distributions",
            records,
            Matchers.hasSize(2)
        );
        MatcherAssert.assertThat(
            "Should contain focal-pkg",
            records.stream().anyMatch(
                r -> "focal-pkg".equals(r.name())
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain bionic-pkg",
            records.stream().anyMatch(
                r -> "bionic-pkg".equals(r.name())
            ),
            Matchers.is(true)
        );
    }

    @Test
    void returnsEmptyForEmptyDirectory(@TempDir final Path temp)
        throws IOException {
        final DebianScanner scanner = new DebianScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "deb-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should return empty stream for empty directory",
            records,
            Matchers.empty()
        );
    }

    @Test
    void prefersPackagesGzOverPackages(@TempDir final Path temp)
        throws IOException {
        final Path dir = temp.resolve("dists/focal/main/binary-amd64");
        Files.createDirectories(dir);
        final String content = String.join(
            "\n",
            "Package: curl",
            "Version: 7.68.0",
            "Size: 100",
            "",
            "Package: wget",
            "Version: 1.20.3",
            "Size: 200",
            ""
        );
        Files.writeString(dir.resolve("Packages"), content);
        final Path gzPath = dir.resolve("Packages.gz");
        try (OutputStream fos = Files.newOutputStream(gzPath);
            GZIPOutputStream gzos = new GZIPOutputStream(fos)) {
            gzos.write(content.getBytes(StandardCharsets.UTF_8));
        }
        final DebianScanner scanner = new DebianScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "deb-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should not double-count when both Packages and Packages.gz exist",
            records,
            Matchers.hasSize(2)
        );
    }
}
