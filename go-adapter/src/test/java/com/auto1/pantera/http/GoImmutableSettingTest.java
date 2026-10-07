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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.index.SyncArtifactIndexer;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.policy.Policy;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The repository {@code immutable} setting on Go module uploads.
 *
 * @since 2.2.10
 */
final class GoImmutableSettingTest {

    /**
     * Module path.
     */
    private static final String MODULE = "example.com/setting/mod";

    /**
     * Storage.
     */
    private Storage storage;

    /**
     * Publish events.
     */
    private Queue<ArtifactEvent> events;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.events = new ConcurrentLinkedQueue<>();
    }

    @ParameterizedTest
    @ValueSource(strings = {"mod", "zip"})
    void immutableRepositoryRefusesDifferentContent(final String ext) {
        final Slice slice = this.upload(true);
        final String path = String.format("%s/@v/v1.0.0.%s", GoImmutableSettingTest.MODULE, ext);
        GoImmutableSettingTest.put(slice, path, "first", Headers.EMPTY);
        MatcherAssert.assertThat(
            "identical re-upload is idempotent",
            GoImmutableSettingTest.put(slice, path, "first", Headers.EMPTY),
            new IsEqual<>(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "different content is a conflict",
            GoImmutableSettingTest.put(slice, path, "second", Headers.EMPTY),
            new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "the published bytes are kept",
            this.read(path), new IsEqual<>("first")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"mod", "zip", "info"})
    void mutableRepositoryOverwrites(final String ext) {
        final Slice slice = this.upload(false);
        final String path = String.format("%s/@v/v1.0.0.%s", GoImmutableSettingTest.MODULE, ext);
        GoImmutableSettingTest.put(slice, path, "first", Headers.EMPTY);
        MatcherAssert.assertThat(
            "different content is accepted",
            GoImmutableSettingTest.put(slice, path, "second", Headers.EMPTY),
            new IsEqual<>(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "the stored bytes are replaced",
            this.read(path), new IsEqual<>("second")
        );
    }

    @Test
    void mutableZipOverwriteKeepsTheVersionListAndPublishes() {
        final Slice slice = this.upload(false);
        final String zip = GoImmutableSettingTest.MODULE + "/@v/v1.0.0.zip";
        GoImmutableSettingTest.put(slice, zip, "first", Headers.EMPTY);
        GoImmutableSettingTest.put(
            slice, GoImmutableSettingTest.MODULE + "/@v/v1.1.0.zip", "other", Headers.EMPTY
        );
        this.events.clear();
        GoImmutableSettingTest.put(slice, zip, "second", Headers.EMPTY);
        MatcherAssert.assertThat(
            "@v/list lists each version once",
            this.read(GoImmutableSettingTest.MODULE + "/@v/list").lines().sorted().toList(),
            new IsEqual<>(java.util.List.of("v1.0.0", "v1.1.0"))
        );
        MatcherAssert.assertThat(
            "the overwrite is published so the search index is upserted",
            this.events.size(), new IsEqual<>(1)
        );
    }

    @Test
    void goSliceThreadsTheImmutableFlag() {
        final Headers auth = Headers.from(new Authorization.Basic("user", "secret"));
        final String path = GoImmutableSettingTest.MODULE + "/@v/v2.0.0.mod";
        final Slice immutable = this.go(true);
        final Slice mutable = this.go(false);
        GoImmutableSettingTest.put(immutable, path, "first", auth);
        MatcherAssert.assertThat(
            "immutable repository refuses different content",
            GoImmutableSettingTest.put(immutable, path, "second", auth),
            new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "mutable repository overwrites",
            GoImmutableSettingTest.put(mutable, path, "second", auth),
            new IsEqual<>(RsStatus.CREATED)
        );
    }

    private Slice upload(final boolean immutable) {
        return new GoUploadSlice(
            this.storage, "go-local", Optional.of(this.events), SyncArtifactIndexer.NOOP,
            immutable
        );
    }

    private Slice go(final boolean immutable) {
        return new GoSlice(
            this.storage, Policy.FREE, new Authentication.Single("user", "secret"), null,
            "go-local", Optional.of(this.events), SyncArtifactIndexer.NOOP, immutable
        );
    }

    private String read(final String path) {
        return new String(
            this.storage.value(new Key.From(path)).join().asBytes(), StandardCharsets.UTF_8
        );
    }

    private static RsStatus put(
        final Slice slice, final String path, final String body, final Headers headers
    ) {
        return slice.response(
            new RequestLine("PUT", "/" + path), headers,
            new Content.From(body.getBytes(StandardCharsets.UTF_8))
        ).join().status();
    }
}
