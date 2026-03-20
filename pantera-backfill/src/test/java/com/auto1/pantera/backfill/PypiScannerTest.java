/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.backfill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link PypiScanner}.
 *
 * @since 1.20.13
 */
final class PypiScannerTest {

    @Test
    void parsesWheelFilename(@TempDir final Path temp) throws IOException {
        final Path pkgDir = temp.resolve("my-package");
        Files.createDirectories(pkgDir);
        Files.write(
            pkgDir.resolve("my_package-1.0.0-py3-none-any.whl"),
            new byte[50]
        );
        final PypiScanner scanner = new PypiScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "pypi-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce exactly 1 record",
            records,
            Matchers.hasSize(1)
        );
        final ArtifactRecord record = records.get(0);
        MatcherAssert.assertThat(
            "Name should be normalized to my-package",
            record.name(),
            Matchers.is("my-package")
        );
        MatcherAssert.assertThat(
            "Version should be 1.0.0",
            record.version(),
            Matchers.is("1.0.0")
        );
        MatcherAssert.assertThat(
            "Size should be 50",
            record.size(),
            Matchers.is(50L)
        );
        MatcherAssert.assertThat(
            "Repo type should be pypi",
            record.repoType(),
            Matchers.is("pypi")
        );
    }

    @Test
    void parsesSdistTarGz(@TempDir final Path temp) throws IOException {
        final Path pkgDir = temp.resolve("requests");
        Files.createDirectories(pkgDir);
        Files.write(
            pkgDir.resolve("requests-2.28.0.tar.gz"),
            new byte[100]
        );
        final PypiScanner scanner = new PypiScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "pypi-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce exactly 1 record",
            records,
            Matchers.hasSize(1)
        );
        final ArtifactRecord record = records.get(0);
        MatcherAssert.assertThat(
            "Name should be requests",
            record.name(),
            Matchers.is("requests")
        );
        MatcherAssert.assertThat(
            "Version should be 2.28.0",
            record.version(),
            Matchers.is("2.28.0")
        );
    }

    @Test
    void parsesSdistZip(@TempDir final Path temp) throws IOException {
        final Path pkgDir = temp.resolve("foo");
        Files.createDirectories(pkgDir);
        Files.write(
            pkgDir.resolve("foo-3.0.zip"),
            new byte[75]
        );
        final PypiScanner scanner = new PypiScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "pypi-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce exactly 1 record",
            records,
            Matchers.hasSize(1)
        );
        final ArtifactRecord record = records.get(0);
        MatcherAssert.assertThat(
            "Name should be foo",
            record.name(),
            Matchers.is("foo")
        );
        MatcherAssert.assertThat(
            "Version should be 3.0",
            record.version(),
            Matchers.is("3.0")
        );
        MatcherAssert.assertThat(
            "Size should be 75",
            record.size(),
            Matchers.is(75L)
        );
    }

    @Test
    void normalizesPackageName(@TempDir final Path temp)
        throws IOException {
        final Path pkgDir = temp.resolve("My_Package");
        Files.createDirectories(pkgDir);
        Files.write(
            pkgDir.resolve("My_Package-2.0.0-py3-none-any.whl"),
            new byte[30]
        );
        final PypiScanner scanner = new PypiScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "pypi-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce exactly 1 record",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Name should be normalized to my-package",
            records.get(0).name(),
            Matchers.is("my-package")
        );
    }

    @Test
    void skipsNonConformingFilenames(@TempDir final Path temp)
        throws IOException {
        final Path dataDir = temp.resolve("data");
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("readme.txt"), "hello");
        Files.writeString(dataDir.resolve("notes.md"), "notes");
        final PypiScanner scanner = new PypiScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "pypi-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 0 records for non-conforming files",
            records,
            Matchers.empty()
        );
    }

    @Test
    void handlesMultipleVersions(@TempDir final Path temp)
        throws IOException {
        final Path pkgDir = temp.resolve("flask");
        Files.createDirectories(pkgDir);
        Files.write(
            pkgDir.resolve("flask-2.0.0-py3-none-any.whl"),
            new byte[40]
        );
        Files.write(
            pkgDir.resolve("flask-2.1.0.tar.gz"),
            new byte[60]
        );
        Files.write(
            pkgDir.resolve("flask-2.2.0.zip"),
            new byte[80]
        );
        final PypiScanner scanner = new PypiScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "pypi-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 3 records for multiple versions",
            records,
            Matchers.hasSize(3)
        );
        MatcherAssert.assertThat(
            "Should contain version 2.0.0",
            records.stream().anyMatch(
                r -> "2.0.0".equals(r.version())
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain version 2.1.0",
            records.stream().anyMatch(
                r -> "2.1.0".equals(r.version())
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain version 2.2.0",
            records.stream().anyMatch(
                r -> "2.2.0".equals(r.version())
            ),
            Matchers.is(true)
        );
    }

    @Test
    void skipsHiddenFiles(@TempDir final Path temp) throws IOException {
        final Path pkgDir = temp.resolve("hidden");
        Files.createDirectories(pkgDir);
        Files.write(
            pkgDir.resolve(".hidden-1.0.0.tar.gz"),
            new byte[20]
        );
        final PypiScanner scanner = new PypiScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "pypi-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should skip hidden files",
            records,
            Matchers.empty()
        );
    }

    @Test
    void scansVersionedSubdirectoryLayout(@TempDir final Path temp)
        throws IOException {
        // Real Pantera PyPI layout: package-name/version/file
        final Path v100 = temp.resolve("dnssec-validator/1.0.0");
        final Path v101 = temp.resolve("dnssec-validator/1.0.1");
        Files.createDirectories(v100);
        Files.createDirectories(v101);
        Files.write(
            v100.resolve("dnssec_validator-1.0.0-py3-none-any.whl"),
            new byte[30]
        );
        Files.write(
            v100.resolve("dnssec_validator-1.0.0.tar.gz"),
            new byte[40]
        );
        Files.write(
            v101.resolve("dnssec_validator-1.0.1-py3-none-any.whl"),
            new byte[50]
        );
        Files.write(
            v101.resolve("dnssec_validator-1.0.1.tar.gz"),
            new byte[60]
        );
        final PypiScanner scanner = new PypiScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "pypi-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should find all 4 files in versioned subdirs",
            records,
            Matchers.hasSize(4)
        );
        MatcherAssert.assertThat(
            "Should contain version 1.0.0",
            records.stream().anyMatch(
                r -> "1.0.0".equals(r.version())
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain version 1.0.1",
            records.stream().anyMatch(
                r -> "1.0.1".equals(r.version())
            ),
            Matchers.is(true)
        );
    }

    @Test
    void skipsHiddenDirectories(@TempDir final Path temp)
        throws IOException {
        // Real Pantera layout has .meta and .pypi hidden dirs
        final Path metaDir = temp.resolve(".meta/pypi/shards/pkg/1.0.0");
        final Path pypiDir = temp.resolve(".pypi/pkg");
        final Path realDir = temp.resolve("pkg/1.0.0");
        Files.createDirectories(metaDir);
        Files.createDirectories(pypiDir);
        Files.createDirectories(realDir);
        Files.write(
            metaDir.resolve("pkg-1.0.0-py3-none-any.whl.json"),
            "{}".getBytes()
        );
        Files.writeString(
            pypiDir.resolve("pkg.html"),
            "<html></html>"
        );
        Files.write(
            realDir.resolve("pkg-1.0.0-py3-none-any.whl"),
            new byte[25]
        );
        final PypiScanner scanner = new PypiScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "pypi-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should only find the real whl, not files in hidden dirs",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Name should be pkg",
            records.get(0).name(),
            Matchers.is("pkg")
        );
    }
}
