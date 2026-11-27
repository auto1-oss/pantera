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
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests for {@link OrphanScan}: a storage is scanned once, however its
 * repositories spell its config.
 *
 * @since 2.2.15
 */
@Testcontainers
final class OrphanScanTest {

    /**
     * PostgreSQL.
     */
    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();

    /**
     * Database helper.
     */
    private static RepoJobsDb db;

    @BeforeAll
    static void migrate() {
        OrphanScanTest.db = new RepoJobsDb(OrphanScanTest.PG);
    }

    @AfterAll
    static void close() {
        OrphanScanTest.db.close();
    }

    @BeforeEach
    void clean() {
        OrphanScanTest.db.clean();
    }

    @Test
    void bucketReachedThroughTwoSpellingsOfItsConfigIsScannedOnce() {
        this.repo(
            "one",
            "{\"type\":\"s3\",\"bucket\":\"data\",\"endpoint\":\"https://S3.example.test/\","
                + "\"credentials\":{\"type\":\"basic\",\"accessKeyId\":\"a\"}}"
        );
        this.repo(
            "two",
            "{\"type\":\"s3\",\"bucket\":\"data\",\"endpoint\":\"https://s3.example.test\","
                + "\"multipart\":\"false\"}"
        );
        MatcherAssert.assertThat(
            this.unowned(),
            new IsEqual<>(List.of("left-behind"))
        );
    }

    @Test
    void twoBucketsAreTwoStorages() {
        this.repo("one", "{\"type\":\"s3\",\"bucket\":\"data\"}");
        this.repo("two", "{\"type\":\"s3\",\"bucket\":\"other\"}");
        MatcherAssert.assertThat(
            this.unowned(),
            new IsEqual<>(List.of("left-behind", "left-behind"))
        );
    }

    /**
     * Directories the scan reports, when every bucket holds one directory no
     * repository owns.
     * @return Reported directories
     */
    private List<String> unowned() {
        final Map<String, Storage> buckets = new HashMap<>();
        return new OrphanScan(
            OrphanScanTest.db.source(),
            cfg -> buckets.computeIfAbsent(
                cfg.getString("bucket"),
                name -> {
                    final Storage storage = new InMemoryStorage();
                    storage.save(new Key.From("left-behind/old.bin"), new Content.From(new byte[1]))
                        .join();
                    return storage;
                }
            )
        ).scan().stream().map(OrphanScan.Orphan::directory).toList();
    }

    /**
     * Add a repository with a storage.
     * @param name Repository name
     * @param storage Storage config
     */
    private void repo(final String name, final String storage) {
        OrphanScanTest.db.exec(
            "INSERT INTO repositories (name, type, config) VALUES (?, 'file', ?::jsonb)",
            name, String.format("{\"repo\":{\"type\":\"file\",\"storage\":%s}}", storage)
        );
    }
}
