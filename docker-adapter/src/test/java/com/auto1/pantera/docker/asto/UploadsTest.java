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
package com.auto1.pantera.docker.asto;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link Uploads}.
 */
final class UploadsTest {
    /**
     * Slice being tested.
     */
    private Uploads uploads;

    /**
     * Storage.
     */
    private Storage storage;

    /**
     * RepoName.
     */
    private String reponame;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.reponame = "test";
        this.uploads = new Uploads(this.storage, this.reponame);
    }

    @Test
    void checkUniquenessUuids() {
        final String uuid = this.uploads.start()
            .toCompletableFuture().join()
            .uuid();
        final String otheruuid = this.uploads.start()
            .toCompletableFuture().join()
            .uuid();
        MatcherAssert.assertThat(
            uuid.equals(otheruuid),
            new IsEqual<>(false)
        );
    }

    @Test
    void shouldStartNewAstoUpload() {
        final String uuid = this.uploads.start()
            .toCompletableFuture().join()
            .uuid();
        MatcherAssert.assertThat(
            this.storage.list(
                Layout.upload(this.reponame, uuid)
            ).join().isEmpty(),
            new IsEqual<>(false)
        );
    }

    @Test
    void shouldFindUploadByUuid() {
        final String uuid = this.uploads.start()
            .toCompletableFuture().join()
            .uuid();
        MatcherAssert.assertThat(
            this.uploads.get(uuid)
                .toCompletableFuture().join()
                .get().uuid(),
            new IsEqual<>(uuid)
        );
    }

    @Test
    void shouldNotFindUploadByEmptyUuid() {
        MatcherAssert.assertThat(
            this.uploads.get("")
                .toCompletableFuture().join()
                .isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void shouldReturnEmptyOptional() {
        MatcherAssert.assertThat(
            this.uploads.get("uuid")
                .toCompletableFuture().join()
                .isPresent(),
            new IsEqual<>(false)
        );
    }
}
