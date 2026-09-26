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
package com.auto1.pantera.importer;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseException;
import com.auto1.pantera.http.auth.AuthzSlice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import com.auto1.pantera.importer.api.ChecksumPolicy;
import com.auto1.pantera.importer.api.ImportHeaders;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.settings.repo.Repositories;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.commons.codec.binary.Hex;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
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

    private Queue<com.auto1.pantera.scheduling.ArtifactEvent> events;

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
        final byte[] content = "hello-pantera".getBytes(StandardCharsets.UTF_8);
        final String sha256 = digestHex("SHA-256", content);
        final Headers headers = new Headers()
            .add(ImportHeaders.REPO_TYPE, "file")
            .add(ImportHeaders.IDEMPOTENCY_KEY, "id-1")
            .add(ImportHeaders.ARTIFACT_NAME, "hello-pantera.txt")
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
        Assertions.assertEquals(sha256, result.digests().get(com.auto1.pantera.importer.api.DigestType.SHA256));
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

    @Test
    void callerSuppliedRepositoryTypeCannotOverrideTheConfiguredType() {
        // SECURITY (2.2.9): the repository's type comes from its authoritative
        // configuration ("file" here). Before the fix the X-Artipie-Repo-Type
        // header was trusted verbatim and drove path rewriting, digest policy,
        // shard writers and the metadata-regeneration switch — so declaring
        // "gem" against a file repository routed the import into the RubyGems
        // (JRuby) indexer. A mismatched declaration must be refused up front.
        final Headers headers = new Headers()
            .add(ImportHeaders.REPO_TYPE, "gem")
            .add(ImportHeaders.IDEMPOTENCY_KEY, "id-type-confusion")
            .add(ImportHeaders.CHECKSUM_POLICY, ChecksumPolicy.METADATA.name());
        final ImportRequest request = ImportRequest.parse(
            new RequestLine(RqMethod.PUT, "/.import/my-repo/gems/evil-1.0.0.gem"),
            headers
        );
        final ResponseException rejected = Assertions.assertThrows(
            ResponseException.class,
            () -> this.service.importArtifact(
                request, new Content.From("x".getBytes(StandardCharsets.UTF_8))
            ).toCompletableFuture().join()
        );
        Assertions.assertEquals(400, rejected.response().status().code());
        Assertions.assertFalse(
            this.repoStorage.exists(new Key.From("gems/evil-1.0.0.gem")).join(),
            "nothing may be written for a type-confused import"
        );
    }

    @Test
    void importsIntoRepositoryWithoutConfiguredUrl() throws Exception {
        // B54: a repository created through the REST API has no `url:` key.
        // RepoConfig.url() throws IllegalStateException for it, which escaped
        // as a 500 "yaml repo.url is absent" and blocked every import.
        final ImportService noUrl = new ImportService(
            new SingleRepo(
                repoConfig(this.repoStorage, Yaml.createYamlMappingBuilder().build())
            ),
            Optional.empty(),
            Optional.of(this.events)
        );
        final Headers headers = new Headers()
            .add(ImportHeaders.REPO_TYPE, "file")
            .add(ImportHeaders.IDEMPOTENCY_KEY, "id-no-url")
            .add(ImportHeaders.ARTIFACT_NAME, "a.txt");
        final ImportResult result = noUrl.importArtifact(
            ImportRequest.parse(
                new RequestLine(RqMethod.PUT, "/.import/my-repo/dir/a.txt"), headers
            ),
            new Content.From("a".getBytes(StandardCharsets.UTF_8))
        ).toCompletableFuture().get();
        Assertions.assertEquals(ImportStatus.CREATED, result.status());
    }

    @Test
    void auditIdentityIsTheAuthenticatedCallerNotTheOwnerHeader() throws Exception {
        // B53: X-Pantera-Artifact-Owner is caller-controlled. It used to
        // become the event owner, i.e. the audit record's user.name, so any
        // writer could attribute an upload to someone else; the record also
        // lacked client.ip and trace.id.
        final Headers headers = new Headers()
            .add(AuthzSlice.LOGIN_HDR, "alice")
            .add(EcsLoggingSlice.CTX_TRACE_ID_HEADER, "trace-import")
            .add(EcsLoggingSlice.CTX_CLIENT_IP_HEADER, "10.0.0.9")
            .add(ImportHeaders.REPO_TYPE, "file")
            .add(ImportHeaders.IDEMPOTENCY_KEY, "id-owner")
            .add(ImportHeaders.ARTIFACT_NAME, "owned.txt")
            .add(ImportHeaders.ARTIFACT_OWNER, "spoofed_victim");
        this.service.importArtifact(
            ImportRequest.parse(
                new RequestLine(RqMethod.PUT, "/.import/my-repo/dir/owned.txt"), headers
            ),
            new Content.From("o".getBytes(StandardCharsets.UTF_8))
        ).toCompletableFuture().get();
        final ArtifactEvent event = this.events.poll();
        MatcherAssert.assertThat(
            "user.name is the authenticated caller",
            event.owner(), new IsEqual<>("alice")
        );
        MatcherAssert.assertThat(
            "trace.id is the import request's",
            event.traceId(), new IsEqual<>("trace-import")
        );
        MatcherAssert.assertThat(
            "client.ip is the import request's",
            event.clientIp(), new IsEqual<>("10.0.0.9")
        );
    }

    @Test
    void importWithoutArtifactNameIsStillRecorded() throws Exception {
        // B53: X-Pantera-Artifact-Name is optional, but without it no event
        // was enqueued, so the import left no artifact row and no audit record.
        final Headers headers = new Headers()
            .add(AuthzSlice.LOGIN_HDR, "alice")
            .add(ImportHeaders.REPO_TYPE, "file")
            .add(ImportHeaders.IDEMPOTENCY_KEY, "id-unnamed");
        final ImportResult result = this.service.importArtifact(
            ImportRequest.parse(
                new RequestLine(RqMethod.PUT, "/.import/my-repo/dir/unnamed.txt"), headers
            ),
            new Content.From("u".getBytes(StandardCharsets.UTF_8))
        ).toCompletableFuture().get();
        MatcherAssert.assertThat(
            "the import succeeds",
            result.status(), new IsEqual<>(ImportStatus.CREATED)
        );
        final ArtifactEvent event = this.events.poll();
        MatcherAssert.assertThat(
            "the event carries the name a native file upload records (R41)",
            event.artifactName(), new IsEqual<>("dir.unnamed.txt")
        );
        MatcherAssert.assertThat(
            "and its version, UNKNOWN when none is detected, as for a plain upload (R42)",
            event.artifactVersion(), new IsEqual<>("UNKNOWN")
        );
    }

    private static RepoConfig repoConfig(final Storage storage) throws Exception {
        return repoConfig(
            storage,
            Yaml.createYamlMappingBuilder()
                .add("url", "http://localhost:8080/my-repo")
                .build()
        );
    }

    private static RepoConfig repoConfig(final Storage storage, final YamlMapping yaml)
        throws Exception {
        final Constructor<RepoConfig> ctor = RepoConfig.class.getDeclaredConstructor(
            YamlMapping.class, String.class, String.class, Storage.class
        );
        ctor.setAccessible(true);
        return ctor.newInstance(yaml, "my-repo", "file", storage);
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
