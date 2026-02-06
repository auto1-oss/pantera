/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.backfill;

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
 * Tests for {@link GemScanner}.
 *
 * @since 1.20.13
 */
final class GemScannerTest {

    @Test
    void parsesSimpleGemFilename(@TempDir final Path temp) throws IOException {
        final Path gems = temp.resolve("gems");
        Files.createDirectories(gems);
        Files.write(gems.resolve("rake-13.0.6.gem"), new byte[100]);
        final GemScanner scanner = new GemScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "gem-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce exactly 1 record",
            records,
            Matchers.hasSize(1)
        );
        final ArtifactRecord record = records.get(0);
        MatcherAssert.assertThat(
            "Name should be rake",
            record.name(),
            Matchers.is("rake")
        );
        MatcherAssert.assertThat(
            "Version should be 13.0.6",
            record.version(),
            Matchers.is("13.0.6")
        );
        MatcherAssert.assertThat(
            "Size should be 100",
            record.size(),
            Matchers.is(100L)
        );
        MatcherAssert.assertThat(
            "Repo type should be gem",
            record.repoType(),
            Matchers.is("gem")
        );
        MatcherAssert.assertThat(
            "Owner should be system",
            record.owner(),
            Matchers.is("system")
        );
    }

    @Test
    void parsesGemWithHyphenatedName(@TempDir final Path temp)
        throws IOException {
        final Path gems = temp.resolve("gems");
        Files.createDirectories(gems);
        Files.write(gems.resolve("net-http-0.3.2.gem"), new byte[80]);
        final GemScanner scanner = new GemScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "gem-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce exactly 1 record",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Name should be net-http",
            records.get(0).name(),
            Matchers.is("net-http")
        );
        MatcherAssert.assertThat(
            "Version should be 0.3.2",
            records.get(0).version(),
            Matchers.is("0.3.2")
        );
    }

    @Test
    void parsesGemWithPlatform(@TempDir final Path temp)
        throws IOException {
        final Path gems = temp.resolve("gems");
        Files.createDirectories(gems);
        Files.write(
            gems.resolve("nokogiri-1.13.8-x86_64-linux.gem"),
            new byte[200]
        );
        final GemScanner scanner = new GemScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "gem-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce exactly 1 record",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Name should be nokogiri",
            records.get(0).name(),
            Matchers.is("nokogiri")
        );
        MatcherAssert.assertThat(
            "Version should be 1.13.8",
            records.get(0).version(),
            Matchers.is("1.13.8")
        );
    }

    @Test
    void parsesGemWithMultipleHyphensInName(@TempDir final Path temp)
        throws IOException {
        final Path gems = temp.resolve("gems");
        Files.createDirectories(gems);
        Files.write(gems.resolve("ruby-ole-1.2.12.7.gem"), new byte[150]);
        final GemScanner scanner = new GemScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "gem-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce exactly 1 record",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Name should be ruby-ole",
            records.get(0).name(),
            Matchers.is("ruby-ole")
        );
        MatcherAssert.assertThat(
            "Version should be 1.2.12.7",
            records.get(0).version(),
            Matchers.is("1.2.12.7")
        );
    }

    @Test
    void handlesMultipleGems(@TempDir final Path temp) throws IOException {
        final Path gems = temp.resolve("gems");
        Files.createDirectories(gems);
        Files.write(gems.resolve("rails-7.0.4.gem"), new byte[300]);
        Files.write(gems.resolve("rake-13.0.6.gem"), new byte[100]);
        Files.write(
            gems.resolve("activerecord-7.0.4.gem"), new byte[250]
        );
        final GemScanner scanner = new GemScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "gem-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 3 records",
            records,
            Matchers.hasSize(3)
        );
        MatcherAssert.assertThat(
            "Should contain rails",
            records.stream().anyMatch(
                r -> "rails".equals(r.name())
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain rake",
            records.stream().anyMatch(
                r -> "rake".equals(r.name())
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain activerecord",
            records.stream().anyMatch(
                r -> "activerecord".equals(r.name())
            ),
            Matchers.is(true)
        );
    }

    @Test
    void skipsNonGemFiles(@TempDir final Path temp) throws IOException {
        final Path gems = temp.resolve("gems");
        Files.createDirectories(gems);
        Files.writeString(gems.resolve("readme.txt"), "hello");
        Files.writeString(gems.resolve("notes.md"), "notes");
        Files.write(gems.resolve("data.tar.gz"), new byte[50]);
        final GemScanner scanner = new GemScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "gem-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 0 records for non-gem files",
            records,
            Matchers.empty()
        );
    }

    @Test
    void returnsEmptyForEmptyDirectory(@TempDir final Path temp)
        throws IOException {
        final GemScanner scanner = new GemScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "gem-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 0 records for empty directory",
            records,
            Matchers.empty()
        );
    }

    @Test
    void handlesGemsInRootDirectly(@TempDir final Path temp)
        throws IOException {
        Files.write(temp.resolve("rake-13.0.6.gem"), new byte[100]);
        Files.write(temp.resolve("rails-7.0.4.gem"), new byte[200]);
        final GemScanner scanner = new GemScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "gem-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 2 records from root-level gems",
            records,
            Matchers.hasSize(2)
        );
        MatcherAssert.assertThat(
            "Should contain rake",
            records.stream().anyMatch(
                r -> "rake".equals(r.name())
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain rails",
            records.stream().anyMatch(
                r -> "rails".equals(r.name())
            ),
            Matchers.is(true)
        );
    }
}
