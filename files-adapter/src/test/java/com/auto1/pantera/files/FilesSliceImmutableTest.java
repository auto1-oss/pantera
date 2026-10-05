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
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.policy.Policy;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The repository {@code immutable} setting on {@link FilesSlice} uploads.
 *
 * @since 2.2.10
 */
final class FilesSliceImmutableTest {

    /**
     * File path.
     */
    private static final String PATH = "/dir/tool-1.0.tar.gz";

    /**
     * Storage.
     */
    private Storage storage;

    /**
     * Upload events.
     */
    private Queue<ArtifactEvent> events;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.events = new ConcurrentLinkedQueue<>();
    }

    @Test
    void immutableRepositoryRefusesDifferentContent() {
        final Slice slice = this.slice(true);
        this.put(slice, "one");
        final Response second = this.put(slice, "two");
        MatcherAssert.assertThat(
            "different bytes are a conflict",
            second.status(), new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "the refusal names the path",
            new String(second.body().asBytes(), StandardCharsets.UTF_8),
            new StringContains(FilesSliceImmutableTest.PATH)
        );
        MatcherAssert.assertThat(
            "the stored bytes are unchanged",
            this.read(), new IsEqual<>("one")
        );
    }

    @Test
    void immutableRepositoryRefusesSameSizeDifferentContent() {
        final Slice slice = this.slice(true);
        this.put(slice, "aaa");
        MatcherAssert.assertThat(
            "same-size different bytes are a conflict",
            this.put(slice, "bbb").status(), new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "the stored bytes are unchanged",
            this.read(), new IsEqual<>("aaa")
        );
    }

    @Test
    void immutableRepositoryAcceptsAnIdenticalReupload() {
        final Slice slice = this.slice(true);
        this.put(slice, "same");
        this.events.clear();
        MatcherAssert.assertThat(
            "identical re-upload is an idempotent success",
            this.put(slice, "same").status(), new IsEqual<>(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "an idempotent retry is not a new publish",
            this.events.size(), new IsEqual<>(0)
        );
    }

    @Test
    void immutableRepositoryAcceptsANewFile() {
        final Slice slice = this.slice(true);
        MatcherAssert.assertThat(
            "first upload accepted",
            this.put(slice, "first").status(), new IsEqual<>(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "first upload is published",
            this.events.size(), new IsEqual<>(1)
        );
    }

    @Test
    void mutableRepositoryOverwrites() {
        final Slice slice = this.slice(false);
        this.put(slice, "one");
        this.events.clear();
        MatcherAssert.assertThat(
            "re-upload with different bytes accepted",
            this.put(slice, "two").status(), new IsEqual<>(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "the stored bytes are replaced",
            this.read(), new IsEqual<>("two")
        );
        MatcherAssert.assertThat(
            "the overwrite is published",
            this.events.size(), new IsEqual<>(1)
        );
    }

    @Test
    void defaultConstructorKeepsOverwriting() {
        final Slice slice = new FilesSlice(
            this.storage, Policy.FREE,
            (username, password) -> Optional.of(new AuthUser(username, "test")),
            null, FilesSlice.ANY_REPO, Optional.of(this.events)
        );
        this.put(slice, "one");
        MatcherAssert.assertThat(
            "re-upload accepted",
            this.put(slice, "two").status(), new IsEqual<>(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "the stored bytes are replaced",
            this.read(), new IsEqual<>("two")
        );
    }

    private Slice slice(final boolean immutable) {
        return new FilesSlice(
            this.storage, Policy.FREE,
            (username, password) -> Optional.of(new AuthUser(username, "test")),
            null, FilesSlice.ANY_REPO, Optional.of(this.events), immutable
        );
    }

    private Response put(final Slice slice, final String body) {
        final byte[] data = body.getBytes(StandardCharsets.UTF_8);
        return slice.response(
            new RequestLine(RqMethod.PUT, FilesSliceImmutableTest.PATH),
            Headers.from(
                new Authorization.Basic("alice", "secret"),
                new ContentLength(data.length)
            ),
            new Content.From(data)
        ).join();
    }

    private String read() {
        return new String(
            this.storage.value(new Key.From(FilesSliceImmutableTest.PATH.substring(1)))
                .join().asBytes(),
            StandardCharsets.UTF_8
        );
    }
}
