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
package com.auto1.pantera.files;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.security.policy.Policy;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FilesSlice} serving and listing.
 *
 * @since 2.2.9
 */
final class FilesSliceTest {

    /**
     * Size of the stored blob.
     */
    private static final int SIZE = 3000;

    /**
     * Storage.
     */
    private Storage storage;

    /**
     * Slice under test.
     */
    private Slice slice;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.storage.save(
            new Key.From("dir", "b.bin"), new Content.From(new byte[FilesSliceTest.SIZE])
        ).join();
        this.slice = new FilesSlice(
            this.storage, Policy.FREE, (username, password) -> Optional.of(new AuthUser(username, "test")),
            FilesSlice.ANY_REPO, Optional.empty()
        );
    }

    @Test
    void headReportsStoredContentLength() {
        MatcherAssert.assertThat(
            this.head("/dir/b.bin").headers().values("Content-Length"),
            new IsEqual<>(java.util.List.of(String.valueOf(FilesSliceTest.SIZE)))
        );
    }

    @Test
    void headWithMetaReportsStoredContentLength() {
        MatcherAssert.assertThat(
            this.head("/dir/b.bin?meta=true").headers().values("Content-Length"),
            new IsEqual<>(java.util.List.of(String.valueOf(FilesSliceTest.SIZE)))
        );
    }

    /**
     * Basic credentials of a test user.
     * @return Headers
     */
    private static Headers auth() {
        return Headers.from(new Authorization.Basic("alice", "secret"));
    }

    /**
     * Issue a HEAD.
     * @param path Path
     * @return Response
     */
    private Response head(final String path) {
        return this.slice.response(
            new RequestLine(RqMethod.HEAD, path), FilesSliceTest.auth(), Content.EMPTY
        ).join();
    }
}
