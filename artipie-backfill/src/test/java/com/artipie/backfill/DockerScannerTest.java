/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.backfill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link DockerScanner}.
 *
 * @since 1.20.13
 */
final class DockerScannerTest {

    @Test
    void scansImageWithTag(@TempDir final Path temp) throws IOException {
        final String digest = "sha256:abc123def456";
        DockerScannerTest.createTagLink(temp, "nginx", "latest", digest);
        final String manifest = String.join(
            "\n",
            "{",
            "  \"schemaVersion\": 2,",
            "  \"config\": { \"size\": 7023, \"digest\": \"sha256:config1\" },",
            "  \"layers\": [",
            "    { \"size\": 32654, \"digest\": \"sha256:layer1\" },",
            "    { \"size\": 73109, \"digest\": \"sha256:layer2\" }",
            "  ]",
            "}"
        );
        DockerScannerTest.createBlob(temp, digest, manifest);
        final DockerScanner scanner = new DockerScanner(true);
        final List<ArtifactRecord> records = scanner.scan(temp, "docker-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce exactly 1 record",
            records,
            Matchers.hasSize(1)
        );
        final ArtifactRecord record = records.get(0);
        MatcherAssert.assertThat(
            "Image name should be nginx",
            record.name(),
            Matchers.is("nginx")
        );
        MatcherAssert.assertThat(
            "Version should be the tag name",
            record.version(),
            Matchers.is("latest")
        );
        MatcherAssert.assertThat(
            "Size should be config + layers sum",
            record.size(),
            Matchers.is(7023L + 32654L + 73109L)
        );
        MatcherAssert.assertThat(
            "Repo type should be docker-proxy",
            record.repoType(),
            Matchers.is("docker-proxy")
        );
        MatcherAssert.assertThat(
            "Repo name should be docker-repo",
            record.repoName(),
            Matchers.is("docker-repo")
        );
    }

    @Test
    void scansMultipleTagsForImage(@TempDir final Path temp)
        throws IOException {
        final String digest1 = "sha256:aaa111bbb222";
        final String digest2 = "sha256:ccc333ddd444";
        DockerScannerTest.createTagLink(temp, "nginx", "latest", digest1);
        DockerScannerTest.createTagLink(temp, "nginx", "1.25", digest2);
        final String manifest1 = String.join(
            "\n",
            "{",
            "  \"schemaVersion\": 2,",
            "  \"config\": { \"size\": 1000, \"digest\": \"sha256:cfg1\" },",
            "  \"layers\": [",
            "    { \"size\": 2000, \"digest\": \"sha256:l1\" }",
            "  ]",
            "}"
        );
        final String manifest2 = String.join(
            "\n",
            "{",
            "  \"schemaVersion\": 2,",
            "  \"config\": { \"size\": 500, \"digest\": \"sha256:cfg2\" },",
            "  \"layers\": [",
            "    { \"size\": 1500, \"digest\": \"sha256:l2\" }",
            "  ]",
            "}"
        );
        DockerScannerTest.createBlob(temp, digest1, manifest1);
        DockerScannerTest.createBlob(temp, digest2, manifest2);
        final DockerScanner scanner = new DockerScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "docker-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 2 records",
            records,
            Matchers.hasSize(2)
        );
        MatcherAssert.assertThat(
            "Should contain 'latest' as version",
            records.stream().anyMatch(
                r -> "latest".equals(r.version())
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain '1.25' as version",
            records.stream().anyMatch(
                r -> "1.25".equals(r.version())
            ),
            Matchers.is(true)
        );
        final ArtifactRecord first = records.stream()
            .filter(r -> "latest".equals(r.version()))
            .findFirst().orElseThrow();
        MatcherAssert.assertThat(
            "latest tag size should be 3000",
            first.size(),
            Matchers.is(3000L)
        );
        final ArtifactRecord second = records.stream()
            .filter(r -> "1.25".equals(r.version()))
            .findFirst().orElseThrow();
        MatcherAssert.assertThat(
            "1.25 tag size should be 2000",
            second.size(),
            Matchers.is(2000L)
        );
    }

    @Test
    void handlesMissingBlob(@TempDir final Path temp) throws IOException {
        final String digest = "sha256:deadbeef0000";
        DockerScannerTest.createTagLink(temp, "alpine", "3.18", digest);
        final DockerScanner scanner = new DockerScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "docker-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 1 record even with missing blob",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Size should be 0 when blob is missing",
            records.get(0).size(),
            Matchers.is(0L)
        );
    }

    @Test
    void handlesManifestList(@TempDir final Path temp) throws IOException {
        final String childDigest = "sha256:child111222333";
        final String childManifest = String.join(
            "\n",
            "{",
            "  \"schemaVersion\": 2,",
            "  \"mediaType\": \"application/vnd.oci.image.manifest.v1+json\",",
            "  \"config\": { \"size\": 1504, \"digest\": \"sha256:cfgchild\" },",
            "  \"layers\": [",
            "    { \"size\": 28865120, \"digest\": \"sha256:layerchild\" }",
            "  ]",
            "}"
        );
        DockerScannerTest.createBlob(temp, childDigest, childManifest);
        final String attestDigest = "sha256:attest999888777";
        final String attestManifest = String.join(
            "\n",
            "{",
            "  \"schemaVersion\": 2,",
            "  \"mediaType\": \"application/vnd.oci.image.manifest.v1+json\",",
            "  \"config\": { \"size\": 167, \"digest\": \"sha256:cfgattest\" },",
            "  \"layers\": [",
            "    { \"size\": 1331, \"digest\": \"sha256:layerattest\",",
            "      \"mediaType\": \"application/vnd.in-toto+json\" }",
            "  ]",
            "}"
        );
        DockerScannerTest.createBlob(temp, attestDigest, attestManifest);
        final String listDigest = "sha256:ffee00112233";
        final String manifestList = String.join(
            "\n",
            "{",
            "  \"schemaVersion\": 2,",
            "  \"mediaType\": \"application/vnd.docker.distribution.manifest.list.v2+json\",",
            "  \"manifests\": [",
            "    { \"digest\": \"" + childDigest + "\", \"size\": 482,",
            "      \"platform\": { \"architecture\": \"amd64\", \"os\": \"linux\" } },",
            "    { \"digest\": \"" + attestDigest + "\", \"size\": 566,",
            "      \"platform\": { \"architecture\": \"unknown\", \"os\": \"unknown\" } }",
            "  ]",
            "}"
        );
        DockerScannerTest.createTagLink(temp, "ubuntu", "22.04", listDigest);
        DockerScannerTest.createBlob(temp, listDigest, manifestList);
        final DockerScanner scanner = new DockerScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "docker-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 1 record",
            records,
            Matchers.hasSize(1)
        );
        final ArtifactRecord record = records.get(0);
        MatcherAssert.assertThat(
            "Size should be sum of ALL child manifests' layers and configs",
            record.size(),
            Matchers.is(1504L + 28865120L + 167L + 1331L)
        );
    }

    @Test
    void handlesNestedImageName(@TempDir final Path temp) throws IOException {
        final String digest = "sha256:1122334455aa";
        DockerScannerTest.createTagLink(temp, "library/redis", "7.0", digest);
        final String manifest = String.join(
            "\n",
            "{",
            "  \"schemaVersion\": 2,",
            "  \"config\": { \"size\": 500, \"digest\": \"sha256:rcfg\" },",
            "  \"layers\": [",
            "    { \"size\": 10000, \"digest\": \"sha256:rl1\" }",
            "  ]",
            "}"
        );
        DockerScannerTest.createBlob(temp, digest, manifest);
        final DockerScanner scanner = new DockerScanner(true);
        final List<ArtifactRecord> records = scanner.scan(temp, "docker-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 1 record",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Image name should include nested path",
            records.get(0).name(),
            Matchers.is("library/redis")
        );
        MatcherAssert.assertThat(
            "Version should be the tag name",
            records.get(0).version(),
            Matchers.is("7.0")
        );
    }

    @Test
    void scansDockerRegistryV2Layout(@TempDir final Path temp)
        throws IOException {
        final String digest = "sha256:abcdef123456";
        final Path v2 = temp.resolve("docker/registry/v2");
        final Path linkDir = v2
            .resolve("repositories/ubuntu/_manifests/tags/latest/current");
        Files.createDirectories(linkDir);
        Files.writeString(
            linkDir.resolve("link"), digest, StandardCharsets.UTF_8
        );
        final String manifest = String.join(
            "\n",
            "{",
            "  \"schemaVersion\": 2,",
            "  \"config\": { \"size\": 2000, \"digest\": \"sha256:c1\" },",
            "  \"layers\": [",
            "    { \"size\": 50000, \"digest\": \"sha256:l1\" }",
            "  ]",
            "}"
        );
        final String[] parts = digest.split(":", 2);
        final Path blobDir = v2.resolve("blobs")
            .resolve(parts[0])
            .resolve(parts[1].substring(0, 2))
            .resolve(parts[1]);
        Files.createDirectories(blobDir);
        Files.writeString(
            blobDir.resolve("data"), manifest, StandardCharsets.UTF_8
        );
        final DockerScanner scanner = new DockerScanner(true);
        final List<ArtifactRecord> records = scanner.scan(temp, "docker-cache")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should find image in docker/registry/v2 layout",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Image name should be ubuntu",
            records.get(0).name(),
            Matchers.is("ubuntu")
        );
        MatcherAssert.assertThat(
            "Size should be config + layer",
            records.get(0).size(),
            Matchers.is(52000L)
        );
    }

    @Test
    void handlesMissingRepositoriesDir(@TempDir final Path temp)
        throws IOException {
        final DockerScanner scanner = new DockerScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "docker-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 0 records when repositories dir is missing",
            records,
            Matchers.empty()
        );
    }

    /**
     * Create a tag link file in the Docker registry layout.
     *
     * @param root Root directory (contains repositories/ and blobs/)
     * @param imageName Image name (e.g., "nginx" or "library/redis")
     * @param tag Tag name (e.g., "latest")
     * @param digest Digest string (e.g., "sha256:abc123")
     * @throws IOException If an I/O error occurs
     */
    private static void createTagLink(final Path root,
        final String imageName, final String tag, final String digest)
        throws IOException {
        final Path linkDir = root
            .resolve("repositories")
            .resolve(imageName)
            .resolve("_manifests")
            .resolve("tags")
            .resolve(tag)
            .resolve("current");
        Files.createDirectories(linkDir);
        Files.writeString(
            linkDir.resolve("link"), digest, StandardCharsets.UTF_8
        );
    }

    /**
     * Create a blob data file for a given digest.
     *
     * @param root Root directory (contains repositories/ and blobs/)
     * @param digest Digest string (e.g., "sha256:abc123def456")
     * @param content Blob content (manifest JSON)
     * @throws IOException If an I/O error occurs
     */
    private static void createBlob(final Path root, final String digest,
        final String content) throws IOException {
        final Path dataPath = DockerScannerTest.blobDataPath(root, digest);
        Files.createDirectories(dataPath.getParent());
        Files.writeString(dataPath, content, StandardCharsets.UTF_8);
    }

    /**
     * Compute the blob data path for a given digest.
     *
     * @param root Root directory
     * @param digest Digest string
     * @return Path to the data file
     */
    private static Path blobDataPath(final Path root, final String digest) {
        final String[] parts = digest.split(":", 2);
        final String algorithm = parts[0];
        final String hex = parts[1];
        return root.resolve("blobs")
            .resolve(algorithm)
            .resolve(hex.substring(0, 2))
            .resolve(hex)
            .resolve("data");
    }
}
