/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.memory.InMemoryStorage;
import com.artipie.asto.SubStorage;
import com.artipie.http.Headers;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.artipie.importer.api.ChecksumPolicy;
import com.artipie.importer.api.ImportHeaders;
import com.artipie.settings.repo.RepoConfig;
import com.artipie.settings.repo.Repositories;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ImportService} using in-memory storage.
 */
final class ImportServiceTest {

    private Storage root;

    private Storage repoStorage;

    private ImportService service;

    private Queue<com.artipie.scheduling.ArtifactEvent> events;

    @BeforeEach
    void setUp() throws Exception {
        this.root = new InMemoryStorage();
        this.repoStorage = new SubStorage(new Key.From("my-repo"), this.root);
        final RepoConfig config = repoConfig(this.repoStorage);
        final Repositories repositories = new SingleRepo(config);
        this.events = new ConcurrentLinkedQueue<>();
        this.service = new ImportService(repositories, Optional.empty(), Optional.of(this.events));
    }

    @Test
    void importsArtifactWithComputedDigest() throws Exception {
        final byte[] content = "hello-artipie".getBytes(StandardCharsets.UTF_8);
        final String sha256 = digestHex("SHA-256", content);
        final Headers headers = new Headers()
            .add(ImportHeaders.REPO_TYPE, "file")
            .add(ImportHeaders.IDEMPOTENCY_KEY, "id-1")
            .add(ImportHeaders.ARTIFACT_NAME, "hello-artipie.txt")
            .add(ImportHeaders.ARTIFACT_VERSION, "1.0.0")
            .add(ImportHeaders.ARTIFACT_OWNER, "qa")
            .add(ImportHeaders.CHECKSUM_POLICY, ChecksumPolicy.COMPUTE.name())
            .add(ImportHeaders.CHECKSUM_SHA256, sha256);
        final ImportRequest request = ImportRequest.parse(
            new RequestLine(RqMethod.PUT, "/.import/my-repo/dist/hello.txt"),
            headers
        );
        final ImportResult result = this.service.importArtifact(
            request,
            new Content.From(content)
        ).toCompletableFuture().get();
        Assertions.assertEquals(ImportStatus.CREATED, result.status());
        Assertions.assertTrue(this.repoStorage.exists(new Key.From("dist/hello.txt")).join());
        Assertions.assertEquals(sha256, result.digests().get(com.artipie.importer.api.DigestType.SHA256));
        Assertions.assertEquals(1, this.events.size());
    }

    @Test
    void quarantinesOnChecksumMismatch() throws Exception {
        final byte[] content = "broken".getBytes(StandardCharsets.UTF_8);
        final Headers headers = new Headers()
            .add(ImportHeaders.REPO_TYPE, "file")
            .add(ImportHeaders.IDEMPOTENCY_KEY, "id-2")
            .add(ImportHeaders.ARTIFACT_NAME, "broken.txt")
            .add(ImportHeaders.CHECKSUM_POLICY, ChecksumPolicy.COMPUTE.name())
            .add(ImportHeaders.CHECKSUM_SHA256, "deadbeef");
        final ImportRequest request = ImportRequest.parse(
            new RequestLine(RqMethod.PUT, "/.import/my-repo/files/broken.txt"),
            headers
        );
        final ImportResult result = this.service.importArtifact(
            request,
            new Content.From(content)
        ).toCompletableFuture().get();
        Assertions.assertEquals(ImportStatus.CHECKSUM_MISMATCH, result.status());
        Assertions.assertTrue(result.quarantineKey().isPresent());
        Assertions.assertFalse(this.repoStorage.exists(new Key.From("files/broken.txt")).join());
        Assertions.assertTrue(
            this.root.list(new Key.From(".import", "quarantine")).join().stream().anyMatch(
                key -> key.string().contains("id-2")
            )
        );
        Assertions.assertTrue(this.events.isEmpty());
    }

    private static RepoConfig repoConfig(final Storage storage) throws Exception {
        final Constructor<RepoConfig> ctor = RepoConfig.class.getDeclaredConstructor(
            YamlMapping.class, String.class, String.class, Storage.class
        );
        ctor.setAccessible(true);
        return ctor.newInstance(
            Yaml.createYamlMappingBuilder()
                .add("url", "http://localhost:8080/my-repo")
                .build(),
            "my-repo",
            "file",
            storage
        );
    }

    private static String digestHex(final String algorithm, final byte[] data) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance(algorithm);
        digest.update(data);
        return Hex.encodeHexString(digest.digest());
    }

    /**
     * Single repository registry for tests.
     */
    private static final class SingleRepo implements Repositories {

        private final RepoConfig repo;

        SingleRepo(final RepoConfig repo) {
            this.repo = repo;
        }

        @Override
        public Optional<RepoConfig> config(final String name) {
            return "my-repo".equals(name) ? Optional.of(this.repo) : Optional.empty();
        }

        @Override
        public java.util.Collection<RepoConfig> configs() {
            return java.util.List.of(this.repo);
        }

        @Override
        public void refresh() {
            // no-op
        }
    }
}
