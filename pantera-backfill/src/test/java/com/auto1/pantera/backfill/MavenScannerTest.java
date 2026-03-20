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
 * Tests for {@link MavenScanner}.
 *
 * @since 1.20.13
 */
final class MavenScannerTest {

    @Test
    void scansMultipleVersions(@TempDir final Path temp) throws IOException {
        final Path v1 = temp.resolve("com/test/logger/1.0");
        final Path v2 = temp.resolve("com/test/logger/2.0");
        Files.createDirectories(v1);
        Files.createDirectories(v2);
        Files.write(v1.resolve("logger-1.0.jar"), new byte[100]);
        Files.write(v1.resolve("logger-1.0.pom"), new byte[20]);
        Files.write(v2.resolve("logger-2.0.jar"), new byte[200]);
        Files.write(v2.resolve("logger-2.0.pom"), new byte[25]);
        final MavenScanner scanner = new MavenScanner("maven");
        final List<ArtifactRecord> records = scanner.scan(temp, "my-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 2 records",
            records,
            Matchers.hasSize(2)
        );
        final ArtifactRecord first = records.stream()
            .filter(r -> "1.0".equals(r.version()))
            .findFirst()
            .orElseThrow();
        MatcherAssert.assertThat(
            "Name should be groupId.artifactId",
            first.name(),
            Matchers.is("com.test.logger")
        );
        MatcherAssert.assertThat(
            "Size should be JAR size (100), not POM",
            first.size(),
            Matchers.is(100L)
        );
        MatcherAssert.assertThat(
            "Repo type should be maven",
            first.repoType(),
            Matchers.is("maven")
        );
        final ArtifactRecord second = records.stream()
            .filter(r -> "2.0".equals(r.version()))
            .findFirst()
            .orElseThrow();
        MatcherAssert.assertThat(
            "Size of version 2.0 jar should be 200",
            second.size(),
            Matchers.is(200L)
        );
    }

    @Test
    void handlesMultipleArtifacts(@TempDir final Path temp)
        throws IOException {
        final Path commonsDir = temp.resolve(
            "org/apache/commons/commons-lang3/3.12.0"
        );
        Files.createDirectories(commonsDir);
        Files.write(
            commonsDir.resolve("commons-lang3-3.12.0.jar"), new byte[50]
        );
        Files.write(
            commonsDir.resolve("commons-lang3-3.12.0.pom"), new byte[10]
        );
        final Path guavaDir = temp.resolve("com/google/guava/guava/31.0");
        Files.createDirectories(guavaDir);
        Files.write(guavaDir.resolve("guava-31.0.jar"), new byte[75]);
        Files.write(guavaDir.resolve("guava-31.0.pom"), new byte[15]);
        final MavenScanner scanner = new MavenScanner("maven");
        final List<ArtifactRecord> records = scanner.scan(temp, "central")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should find records from both artifacts",
            records,
            Matchers.hasSize(2)
        );
        MatcherAssert.assertThat(
            "Should contain commons-lang3",
            records.stream()
                .anyMatch(r -> "org.apache.commons.commons-lang3".equals(r.name())),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain guava",
            records.stream()
                .anyMatch(r -> "com.google.guava.guava".equals(r.name())),
            Matchers.is(true)
        );
    }

    @Test
    void handlesWarFile(@TempDir final Path temp) throws IOException {
        final Path ver = temp.resolve("com/test/webapp/1.0");
        Files.createDirectories(ver);
        Files.write(ver.resolve("webapp-1.0.war"), new byte[300]);
        final MavenScanner scanner = new MavenScanner("maven");
        final List<ArtifactRecord> records = scanner.scan(temp, "repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should find the war artifact",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "War file size should be 300",
            records.get(0).size(),
            Matchers.is(300L)
        );
    }

    @Test
    void gradleUsesCorrectRepoType(@TempDir final Path temp)
        throws IOException {
        final Path ver = temp.resolve("com/test/gradlelib/1.0");
        Files.createDirectories(ver);
        Files.write(ver.resolve("gradlelib-1.0.jar"), new byte[50]);
        final MavenScanner scanner = new MavenScanner("gradle");
        final List<ArtifactRecord> records = scanner.scan(temp, "repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce a record",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Repo type should be gradle",
            records.get(0).repoType(),
            Matchers.is("gradle")
        );
    }

    @Test
    void skipsSidecarFiles(@TempDir final Path temp) throws IOException {
        final Path dir = temp.resolve("uk/co/datumedge/hamcrest-json/0.2");
        Files.createDirectories(dir);
        Files.write(dir.resolve("hamcrest-json-0.2.jar"), new byte[200]);
        Files.write(dir.resolve("hamcrest-json-0.2.pom"), new byte[30]);
        Files.writeString(dir.resolve("hamcrest-json-0.2.jar.sha1"), "hash");
        Files.writeString(dir.resolve("hamcrest-json-0.2.jar.sha256"), "hash");
        Files.writeString(dir.resolve("hamcrest-json-0.2.jar.md5"), "hash");
        Files.writeString(
            dir.resolve("hamcrest-json-0.2.jar.artipie-meta.json"), "{}"
        );
        final MavenScanner scanner = new MavenScanner("maven");
        final List<ArtifactRecord> records = scanner.scan(temp, "proxy-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce exactly 1 deduplicated record",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Name should be uk.co.datumedge.hamcrest-json",
            records.get(0).name(),
            Matchers.is("uk.co.datumedge.hamcrest-json")
        );
        MatcherAssert.assertThat(
            "Size should be JAR size (200), not POM (30)",
            records.get(0).size(),
            Matchers.is(200L)
        );
    }

    @Test
    void handlesPomOnlyArtifacts(@TempDir final Path temp)
        throws IOException {
        final Path dir = temp.resolve(
            "com/fasterxml/jackson/jackson-bom/3.0.1"
        );
        Files.createDirectories(dir);
        Files.write(dir.resolve("jackson-bom-3.0.1.pom"), new byte[80]);
        Files.writeString(dir.resolve("jackson-bom-3.0.1.pom.sha1"), "hash");
        final MavenScanner scanner = new MavenScanner("maven");
        final List<ArtifactRecord> records = scanner.scan(temp, "proxy-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should find the POM-only artifact",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Name should be com.fasterxml.jackson.jackson-bom",
            records.get(0).name(),
            Matchers.is("com.fasterxml.jackson.jackson-bom")
        );
        MatcherAssert.assertThat(
            "Size should be the POM size",
            records.get(0).size(),
            Matchers.is(80L)
        );
    }

    @Test
    void skipsMetadataXmlFiles(@TempDir final Path temp)
        throws IOException {
        // Plugin-level or artifact-level metadata files should not
        // be indexed as artifacts themselves
        final Path pluginDir = temp.resolve("com/example/maven/plugins");
        Files.createDirectories(pluginDir);
        Files.writeString(
            pluginDir.resolve("maven-metadata.xml"),
            "<?xml version=\"1.0\"?><metadata><plugins></plugins></metadata>"
        );
        final Path artifactDir = temp.resolve(
            "com/example/maven/plugins/my-plugin/1.0"
        );
        Files.createDirectories(artifactDir);
        Files.write(artifactDir.resolve("my-plugin-1.0.jar"), new byte[150]);
        Files.write(artifactDir.resolve("my-plugin-1.0.pom"), new byte[20]);
        final MavenScanner scanner = new MavenScanner("maven");
        final List<ArtifactRecord> records = scanner.scan(temp, "repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should only find the actual artifact, not the metadata XML",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Should be the plugin JAR",
            records.get(0).name(),
            Matchers.is("com.example.maven.plugins.my-plugin")
        );
    }

    @Test
    void handlesZipArtifactsWithSidecars(@TempDir final Path temp)
        throws IOException {
        final Path v1 = temp.resolve(
            "com/auto1/aws/lambda/rackspace_swift_uploader_lambda/1.2.10"
        );
        final Path v2 = temp.resolve(
            "com/auto1/aws/lambda/rackspace_swift_uploader_lambda/1.2.10-beta"
        );
        Files.createDirectories(v1);
        Files.createDirectories(v2);
        Files.write(
            v1.resolve(
                "rackspace_swift_uploader_lambda_1.2.10.zip"
            ), new byte[400]
        );
        Files.writeString(
            v1.resolve(
                "rackspace_swift_uploader_lambda_1.2.10.zip.md5"
            ), "hash"
        );
        Files.writeString(
            v1.resolve(
                "rackspace_swift_uploader_lambda_1.2.10.zip.sha1"
            ), "hash"
        );
        Files.writeString(
            v1.resolve(
                "rackspace_swift_uploader_lambda_1.2.10.zip.sha256"
            ), "hash"
        );
        Files.write(
            v2.resolve(
                "rackspace_swift_uploader_lambda_1.2.10-beta.zip"
            ), new byte[350]
        );
        Files.writeString(
            v2.resolve(
                "rackspace_swift_uploader_lambda_1.2.10-beta.zip.md5"
            ), "hash"
        );
        final MavenScanner scanner = new MavenScanner("gradle");
        final List<ArtifactRecord> records = scanner.scan(temp, "ops")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should find 2 zip versions",
            records,
            Matchers.hasSize(2)
        );
        MatcherAssert.assertThat(
            "Name should be fully qualified",
            records.get(0).name(),
            Matchers.is(
                "com.auto1.aws.lambda:rackspace_swift_uploader_lambda"
            )
        );
        MatcherAssert.assertThat(
            "Repo type should be gradle",
            records.get(0).repoType(),
            Matchers.is("gradle")
        );
        final ArtifactRecord release = records.stream()
            .filter(r -> "1.2.10".equals(r.version()))
            .findFirst().orElseThrow();
        MatcherAssert.assertThat(
            "Release zip size should be 400",
            release.size(),
            Matchers.is(400L)
        );
        final ArtifactRecord beta = records.stream()
            .filter(r -> "1.2.10-beta".equals(r.version()))
            .findFirst().orElseThrow();
        MatcherAssert.assertThat(
            "Beta zip size should be 350",
            beta.size(),
            Matchers.is(350L)
        );
    }

    @Test
    void returnsEmptyForEmptyDirectory(@TempDir final Path temp)
        throws IOException {
        final MavenScanner scanner = new MavenScanner("maven");
        final List<ArtifactRecord> records = scanner.scan(temp, "empty")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Empty directory should produce no records",
            records,
            Matchers.empty()
        );
    }

    @Test
    void deduplicatesJarAndPom(@TempDir final Path temp) throws IOException {
        final Path dir = temp.resolve("com/test/lib/1.0");
        Files.createDirectories(dir);
        Files.write(dir.resolve("lib-1.0.jar"), new byte[500]);
        Files.write(dir.resolve("lib-1.0.pom"), new byte[50]);
        final MavenScanner scanner = new MavenScanner("maven");
        final List<ArtifactRecord> records = scanner.scan(temp, "repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "JAR + POM should produce exactly 1 record",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Size should be JAR (500), not POM (50)",
            records.get(0).size(),
            Matchers.is(500L)
        );
    }
}
