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
package com.auto1.pantera.composer.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.composer.ComposerBaseUrl;
import com.auto1.pantera.composer.Name;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.index.SyncArtifactIndexer;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.policy.Policy;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The repository {@code immutable} setting on Composer uploads: a mutable
 * repository overwrites a published release and keeps its metadata
 * consistent with the new archive.
 *
 * @since 2.2.10
 */
final class MutableReleaseTest {

    /**
     * Stored archive of qa/helper 1.0.0.
     */
    private static final Key STORED = new Key.From(
        "artifacts", "qa", "helper", "1.0.0", "qa-helper-1.0.0.zip"
    );

    /**
     * Storage.
     */
    private InMemoryStorage storage;

    /**
     * Repository.
     */
    private AstoRepository repository;

    /**
     * Upload events.
     */
    private Queue<ArtifactEvent> events;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.repository = new AstoRepository(this.storage, Optional.of("http://pantera:8080/php"));
        this.events = new ConcurrentLinkedQueue<>();
    }

    @Test
    void mutableRepositoryOverwritesAReleaseArchive() throws Exception {
        final Slice slice = new AddArchiveSlice(
            this.repository, Optional.of(this.events), "php", SyncArtifactIndexer.NOOP, false
        );
        MatcherAssert.assertThat(
            "first upload is created",
            MutableReleaseTest.put(slice, "hi", Headers.EMPTY), new IsEqual<>(201)
        );
        final byte[] before = this.storage.value(MutableReleaseTest.STORED).join().asBytes();
        this.events.clear();
        MatcherAssert.assertThat(
            "a different archive for the release is accepted",
            MutableReleaseTest.put(slice, "hi-CHANGED", Headers.EMPTY), new IsEqual<>(201)
        );
        final byte[] after = this.storage.value(MutableReleaseTest.STORED).join().asBytes();
        MatcherAssert.assertThat(
            "the archive is replaced", after, new IsNot<>(new IsEqual<>(before))
        );
        MatcherAssert.assertThat(
            "the metadata shasum describes the new archive",
            this.shasum(), new IsEqual<>(MutableReleaseTest.sha1(after))
        );
        MatcherAssert.assertThat(
            "the overwrite is published so the search index is upserted",
            this.events.size(), new IsEqual<>(1)
        );
    }

    @Test
    void immutableRepositoryStillRefusesAReleaseArchive() throws Exception {
        final Slice slice = new AddArchiveSlice(
            this.repository, Optional.of(this.events), "php", SyncArtifactIndexer.NOOP, true
        );
        MutableReleaseTest.put(slice, "hi", Headers.EMPTY);
        MatcherAssert.assertThat(
            "identical re-upload is idempotent",
            MutableReleaseTest.put(slice, "hi", Headers.EMPTY), new IsEqual<>(201)
        );
        MatcherAssert.assertThat(
            "different content is a conflict",
            MutableReleaseTest.put(slice, "hi-CHANGED", Headers.EMPTY), new IsEqual<>(409)
        );
    }

    @Test
    void mutableRepositoryOverwritesAJsonRegistration() throws Exception {
        final Slice slice = new AddSlice(this.repository, false);
        final String first = "{\"name\":\"qa/meta\",\"version\":\"1.0.0\","
            + "\"dist\":{\"url\":\"https://example.org/a.zip\",\"type\":\"zip\"}}";
        final String moved = "{\"name\":\"qa/meta\",\"version\":\"1.0.0\","
            + "\"dist\":{\"url\":\"https://example.org/b.zip\",\"type\":\"zip\"}}";
        MutableReleaseTest.json(slice, first);
        MatcherAssert.assertThat(
            "a different entry for the release is accepted",
            MutableReleaseTest.json(slice, moved), new IsEqual<>(201)
        );
        MatcherAssert.assertThat(
            "the metadata holds the new entry",
            this.entry("qa/meta").getJsonObject("dist").getString("url"),
            new IsEqual<>("https://example.org/b.zip")
        );
    }

    @Test
    void phpComposerThreadsTheImmutableFlag() throws Exception {
        final Headers auth = Headers.from(new Authorization.Basic("user", "secret"));
        final Slice immutable = this.php(true);
        final Slice mutable = this.php(false);
        MatcherAssert.assertThat(
            "first upload is created",
            MutableReleaseTest.put(immutable, "hi", auth), new IsEqual<>(201)
        );
        MatcherAssert.assertThat(
            "immutable repository refuses different content",
            MutableReleaseTest.put(immutable, "hi-CHANGED", auth), new IsEqual<>(409)
        );
        MatcherAssert.assertThat(
            "mutable repository overwrites",
            MutableReleaseTest.put(mutable, "hi-CHANGED", auth), new IsEqual<>(201)
        );
    }

    private Slice php(final boolean immutable) {
        return new PhpComposer(
            this.repository, Policy.FREE, new Authentication.Single("user", "secret"), null,
            "php", Optional.of(this.events), SyncArtifactIndexer.NOOP, immutable,
            new ComposerBaseUrl(Optional.empty(), "php")
        );
    }

    private String shasum() {
        return this.entry("qa/helper").getJsonObject("dist").getString("shasum");
    }

    private JsonObject entry(final String pkg) {
        return this.repository.packages(new Name(pkg)).toCompletableFuture().join()
            .orElseThrow().content().toCompletableFuture().join()
            .asJsonObject().getJsonObject("packages").getJsonObject(pkg)
            .getJsonObject("1.0.0");
    }

    private static int put(final Slice slice, final String code, final Headers headers)
        throws Exception {
        return slice.response(
            new RequestLine(RqMethod.PUT, "/qa-helper.zip"), headers,
            new Content.From(
                MutableReleaseTest.zip("{\"name\":\"qa/helper\",\"version\":\"1.0.0\"}", code)
            )
        ).join().status().code();
    }

    private static int json(final Slice slice, final String body) {
        return slice.response(
            new RequestLine(RqMethod.PUT, "/"), Headers.EMPTY,
            new Content.From(body.getBytes(StandardCharsets.UTF_8))
        ).join().status().code();
    }

    private static String sha1(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }

    private static byte[] zip(final String composer, final String code) throws Exception {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("composer.json"));
            zos.write(composer.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("src/Helper.php"));
            zos.write(("<?php return '" + code + "';").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return bos.toByteArray();
    }
}
