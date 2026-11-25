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
package com.auto1.pantera.repojob;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.cache.StoragesCache;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Purge of a deleted repository on an S3 storage, built from the storage
 * config the tombstone keeps, the way a node's runner does it.
 *
 * @since 2.2.14
 */
@Testcontainers
@Timeout(300)
final class RepoPurgeS3Test {

    /**
     * PostgreSQL.
     */
    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();

    /**
     * S3 mock, the same image the S3 storage tests use.
     */
    @Container
    static final GenericContainer<?> S3_MOCK = new GenericContainer<>(
        DockerImageName.parse("adobe/s3mock:3.5.2")
    )
        .withExposedPorts(9090)
        .withEnv("initialBuckets", "purge")
        .waitingFor(Wait.forHttp("/").forPort(9090));

    /**
     * Objects in the deleted repository: more than two delete batches.
     */
    private static final int OBJECTS = 2100;

    /**
     * Database helper.
     */
    private static RepoJobsDb db;

    @BeforeAll
    static void migrate() {
        RepoPurgeS3Test.db = new RepoJobsDb(RepoPurgeS3Test.PG);
    }

    @AfterAll
    static void close() {
        RepoPurgeS3Test.db.close();
    }

    @BeforeEach
    void init() {
        RepoPurgeS3Test.db.clean();
    }

    @Test
    void purgesTheRepositorysObjectsAndLeavesItsSiblingAlone() throws Exception {
        final JsonObject config = RepoPurgeS3Test.storageConfig();
        final Storage raw = new RepoStorages(new StoragesCache()).apply(config);
        RepoPurgeS3Test.fill(raw, "libs", RepoPurgeS3Test.OBJECTS);
        RepoPurgeS3Test.fill(raw, "libs-proxy", 5);
        for (final String name : List.of("libs", "libs-proxy")) {
            RepoPurgeS3Test.db.exec(
                "INSERT INTO repositories (name, type, config) VALUES (?, 'file', ?::jsonb)",
                name,
                Json.createObjectBuilder().add(
                    "repo", Json.createObjectBuilder().add("type", "file").add("storage", config)
                ).build().toString()
            );
        }
        final long job = new RepoLifecycleDao(RepoPurgeS3Test.db.source())
            .delete("libs", false, Duration.ZERO, "admin", null).job();
        try (RepoJobRunner runner = new RepoJobRunner(
            RepoPurgeS3Test.db.source(),
            new RepoStorages(new StoragesCache()).andThen(StoragePurgeStore::new),
            "node-a",
            new RepoJobRunner.Timing(
                Duration.ofHours(1), Duration.ofMinutes(10), Duration.ZERO, Duration.ofHours(1),
                Duration.ofMillis(100), Duration.ofHours(1)
            )
        )) {
            runner.drain();
        }
        final RepoJob done = new RepoJobDao(RepoPurgeS3Test.db.source()).get(job).orElseThrow();
        MatcherAssert.assertThat(
            "the job finished and counted every object",
            List.of(done.state(), String.valueOf(done.objects())),
            new IsEqual<>(List.of(RepoJob.DONE, String.valueOf(RepoPurgeS3Test.OBJECTS)))
        );
        MatcherAssert.assertThat(
            "nothing is left under the repository's directory",
            raw.listPage(new Key.From("libs"), Optional.empty(), 10).join().keys().size(),
            new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "the repository whose name starts the same keeps its objects",
            raw.listPage(new Key.From("libs-proxy"), Optional.empty(), 10).join().keys().size(),
            new IsEqual<>(5)
        );
    }

    /**
     * Storage config of the mock bucket, as a repository would carry it.
     * @return Storage config
     */
    private static JsonObject storageConfig() {
        return Json.createObjectBuilder()
            .add("type", "s3")
            .add("bucket", "purge")
            .add("region", "us-east-1")
            .add(
                "endpoint",
                String.format(
                    "http://%s:%d", RepoPurgeS3Test.S3_MOCK.getHost(),
                    RepoPurgeS3Test.S3_MOCK.getMappedPort(9090)
                )
            )
            .add(
                "credentials",
                Json.createObjectBuilder()
                    .add("type", "basic")
                    .add("accessKeyId", "foo")
                    .add("secretAccessKey", "bar")
            )
            .build();
    }

    /**
     * Write objects under a directory, a bounded number at a time.
     * @param storage Storage
     * @param dir Directory
     * @param count How many
     */
    private static void fill(final Storage storage, final String dir, final int count) {
        final List<CompletableFuture<Void>> wave = new ArrayList<>(64);
        for (int idx = 0; idx < count; idx += 1) {
            wave.add(
                storage.save(
                    new Key.From(String.format("%s/g%02d/a%05d.bin", dir, idx % 37, idx)),
                    new Content.From("x".getBytes(StandardCharsets.UTF_8))
                )
            );
            if (wave.size() == 64 || idx == count - 1) {
                CompletableFuture.allOf(wave.toArray(new CompletableFuture[0])).join();
                wave.clear();
            }
        }
    }
}
