/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer.http;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.SubStorage;
import com.artipie.asto.memory.InMemoryStorage;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.artipie.importer.ImportService;
import com.artipie.importer.api.ChecksumPolicy;
import com.artipie.importer.api.ImportHeaders;
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.settings.repo.RepoConfig;
import com.artipie.settings.repo.Repositories;
import java.lang.reflect.Constructor;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.Json;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ImportSlice}.
 */
final class ImportSliceTest {

    private InMemoryStorage root;

    private ImportSlice slice;

    @BeforeEach
    void init() throws Exception {
        this.root = new InMemoryStorage();
        final Storage repo = new SubStorage(new Key.From("cli-repo"), this.root);
        final RepoConfig cfg = repoConfig(repo);
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final ImportService service = new ImportService(
            new SingleRepo(cfg),
            Optional.empty(),
            Optional.of(events)
        );
        this.slice = new ImportSlice(service);
    }

    @Test
    void returnsCreatedOnSuccess() throws Exception {
        final Headers headers = new Headers()
            .add(ImportHeaders.REPO_TYPE, "file")
            .add(ImportHeaders.IDEMPOTENCY_KEY, "cli-1")
            .add(ImportHeaders.ARTIFACT_NAME, "cli.txt")
            .add(ImportHeaders.CHECKSUM_POLICY, ChecksumPolicy.SKIP.name());
        final Response response = this.slice.response(
            new RequestLine(RqMethod.PUT, "/.import/cli-repo/docs/cli.txt"),
            headers,
            new Content.From("payload".getBytes(StandardCharsets.UTF_8))
        ).get();
        Assertions.assertEquals(com.artipie.http.RsStatus.CREATED, response.status());
        final JsonObject json = readJson(response);
        Assertions.assertEquals("CREATED", json.getString("status"));
        Assertions.assertTrue(this.root.exists(new Key.From("cli-repo/docs/cli.txt")).join());
    }

    @Test
    void returnsNotFoundForMissingRepo() throws Exception {
        final Headers headers = new Headers()
            .add(ImportHeaders.REPO_TYPE, "file")
            .add(ImportHeaders.IDEMPOTENCY_KEY, "cli-2");
        final Response response = this.slice.response(
            new RequestLine(RqMethod.PUT, "/.import/unknown-repo/a.bin"),
            headers,
            Content.EMPTY
        ).get();
        Assertions.assertEquals(com.artipie.http.RsStatus.NOT_FOUND, response.status());
    }

    private static JsonObject readJson(final Response response) {
        try (JsonReader reader = Json.createReader(new StringReader(response.body().asString()))) {
            return reader.readObject();
        }
    }

    private static RepoConfig repoConfig(final Storage storage) throws Exception {
        final Constructor<RepoConfig> ctor = RepoConfig.class.getDeclaredConstructor(
            YamlMapping.class, String.class, String.class, Storage.class
        );
        ctor.setAccessible(true);
        return ctor.newInstance(
            Yaml.createYamlMappingBuilder().build(),
            "cli-repo",
            "file",
            storage
        );
    }

    private static final class SingleRepo implements Repositories {

        private final RepoConfig repo;

        SingleRepo(final RepoConfig repo) {
            this.repo = repo;
        }

        @Override
        public Optional<RepoConfig> config(final String name) {
            return "cli-repo".equals(name) ? Optional.of(this.repo) : Optional.empty();
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
