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
package com.auto1.pantera.npm.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.rx.RxStorageWrapper;
import com.auto1.pantera.npm.proxy.model.NpmAsset;
import com.auto1.pantera.npm.proxy.model.NpmPackage;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.apache.commons.io.IOUtils;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NPM Proxy storage test.
 * @since 0.1
 */
public final class RxNpmProxyStorageTest {
    /**
     * Last modified date for both package and asset.
     */
    private static final String MODIFIED = "Tue, 24 Mar 2020 12:15:16 GMT";

    /**
     * Last refreshed date for package (datetime).
     */
    private static final OffsetDateTime REFRESHED = OffsetDateTime.of(
        LocalDateTime.of(2020, Month.APRIL, 24, 12, 15, 16, 123_456_789),
        ZoneOffset.UTC
    );

    /**
     * Last refreshed date for package (string).
     */
    private static final String REFRESHED_STR = "2020-04-24T12:15:16.123456789Z";

    /**
     * Asset Content-Type.
     */
    private static final String CONTENT_TYPE = "application/octet-stream";

    /**
     * Assert content.
     */
    private static final String DEF_CONTENT = "foobar";

    /**
     * NPM Proxy Storage.
     */
    private NpmProxyStorage storage;

    /**
     * Underlying storage.
     */
    private Storage delegate;

    @Test
    public void savesPackage() throws IOException {
        this.doSavePackage();
        MatcherAssert.assertThat(
            this.publisherAsStr("asdas/meta.json"),
            new IsEqual<>(RxNpmProxyStorageTest.readContent())
        );
        final String metadata = this.publisherAsStr("asdas/meta.meta");
        final JsonObject json = new JsonObject(metadata);
        MatcherAssert.assertThat(
            json.getString("last-modified"),
            new IsEqual<>(RxNpmProxyStorageTest.MODIFIED)
        );
        MatcherAssert.assertThat(
            new JsonObject(metadata).getString("last-refreshed"),
            new IsEqual<>(RxNpmProxyStorageTest.REFRESHED_STR)
        );
    }

    @Test
    public void savesAsset() {
        final String path = "asdas/-/asdas-1.0.0.tgz";
        this.doSaveAsset();
        MatcherAssert.assertThat(
            "Content of asset is correct",
            this.publisherAsStr(path),
            new IsEqual<>(RxNpmProxyStorageTest.DEF_CONTENT)
        );
        final String metadata = this.publisherAsStr("asdas/-/asdas-1.0.0.tgz.meta");
        final JsonObject json = new JsonObject(metadata);
        MatcherAssert.assertThat(
            "Last-modified is correct",
            json.getString("last-modified"),
            new IsEqual<>(RxNpmProxyStorageTest.MODIFIED)
        );
        MatcherAssert.assertThat(
            "Content-type of asset is correct",
            json.getString("content-type"),
            new IsEqual<>(RxNpmProxyStorageTest.CONTENT_TYPE)
        );
    }

    @Test
    public void loadsPackage() throws IOException {
        final String name = "asdas";
        this.doSavePackage();
        final NpmPackage pkg = this.storage.getPackage(name).blockingGet();
        MatcherAssert.assertThat(
            "Package name is correct",
            pkg.name(),
            new IsEqual<>(name)
        );
        MatcherAssert.assertThat(
            "Content of package is correct",
            pkg.content(),
            new IsEqual<>(RxNpmProxyStorageTest.readContent())
        );
        MatcherAssert.assertThat(
            "Modified date is correct",
            pkg.meta().lastModified(),
            new IsEqual<>(RxNpmProxyStorageTest.MODIFIED)
        );
        MatcherAssert.assertThat(
            "Refreshed date is correct",
            pkg.meta().lastRefreshed(),
            new IsEqual<>(RxNpmProxyStorageTest.REFRESHED)
        );
    }

    @Test
    public void loadsAsset() {
        final String path = "asdas/-/asdas-1.0.0.tgz";
        this.doSaveAsset();
        final NpmAsset asset = this.storage.getAsset(path).blockingGet();
        MatcherAssert.assertThat(
            "Path to asset is correct",
            asset.path(),
            new IsEqual<>(path)
        );
        MatcherAssert.assertThat(
            "Content of asset is correct",
            new Content.From(asset.dataPublisher()).asString(),
            new IsEqual<>(RxNpmProxyStorageTest.DEF_CONTENT)
        );
        MatcherAssert.assertThat(
            "Modified date is correct",
            asset.meta().lastModified(),
            new IsEqual<>(RxNpmProxyStorageTest.MODIFIED)
        );
        MatcherAssert.assertThat(
            "Content-type of asset is correct",
            asset.meta().contentType(),
            new IsEqual<>(RxNpmProxyStorageTest.CONTENT_TYPE)
        );
    }

    @Test
    public void failsToLoadPackage() {
        MatcherAssert.assertThat(
            "Unexpected package found",
            this.storage.getPackage("not-found").isEmpty().blockingGet()
        );
    }

    @Test
    public void failsToLoadAsset() {
        MatcherAssert.assertThat(
            "Unexpected package asset",
            this.storage.getAsset("not-found").isEmpty().blockingGet()
        );
    }

    /**
     * Phase 12 — stream-through happy path: subscribing to the tee'd
     * publisher delivers the upstream bytes to the caller AND lands the
     * tarball + meta on disk via background saves.
     */
    @Test
    public void streamThroughTeesBytesToCallerAndStorage() {
        final String path = "asdas/-/asdas-1.0.0.tgz";
        final NpmAsset upstream = new NpmAsset(
            path,
            new Content.From(RxNpmProxyStorageTest.DEF_CONTENT.getBytes(StandardCharsets.UTF_8)),
            RxNpmProxyStorageTest.MODIFIED,
            RxNpmProxyStorageTest.CONTENT_TYPE
        );
        final NpmAsset teed = this.storage.saveStreamThrough(upstream).blockingGet();
        MatcherAssert.assertThat(
            "Stream-through asset preserves upstream path",
            teed.path(),
            new IsEqual<>(path)
        );
        // Subscribe to the tee'd publisher and collect the bytes the client
        // would see.
        final String clientBytes = new Content.From(teed.dataPublisher()).asString();
        MatcherAssert.assertThat(
            "Client receives identical bytes to upstream",
            clientBytes,
            new IsEqual<>(RxNpmProxyStorageTest.DEF_CONTENT)
        );
        // After stream completion, both the tarball and the meta sidecar
        // must be in storage.
        MatcherAssert.assertThat(
            "Tarball written to storage matches upstream bytes",
            this.publisherAsStr(path),
            new IsEqual<>(RxNpmProxyStorageTest.DEF_CONTENT)
        );
        final String metadata = this.publisherAsStr(
            String.format("%s.meta", path)
        );
        final JsonObject json = new JsonObject(metadata);
        MatcherAssert.assertThat(
            "Sidecar last-modified is correct",
            json.getString("last-modified"),
            new IsEqual<>(RxNpmProxyStorageTest.MODIFIED)
        );
        MatcherAssert.assertThat(
            "Sidecar content-type is correct",
            json.getString("content-type"),
            new IsEqual<>(RxNpmProxyStorageTest.CONTENT_TYPE)
        );
    }

    /**
     * Phase 12 — integrity invariant: bytes the client receives MUST equal
     * bytes written to disk, even when the upstream publisher emits multiple
     * chunks of varying size.
     */
    @Test
    public void streamThroughIntegrityWithMultipleChunks() {
        final String path = "multichunk/-/multichunk-1.0.0.tgz";
        final byte[] payload = new byte[64 * 1024 + 17];
        for (int idx = 0; idx < payload.length; idx++) {
            payload[idx] = (byte) (idx & 0xFF);
        }
        // Emit the payload as 4 chunks so the tee's per-buffer copy logic is
        // exercised end-to-end.
        final java.util.List<java.nio.ByteBuffer> chunks = new java.util.ArrayList<>();
        final int chunkSize = payload.length / 4;
        for (int idx = 0; idx < 4; idx++) {
            final int start = idx * chunkSize;
            final int end = idx == 3 ? payload.length : start + chunkSize;
            chunks.add(java.nio.ByteBuffer.wrap(
                java.util.Arrays.copyOfRange(payload, start, end)
            ));
        }
        final NpmAsset upstream = new NpmAsset(
            path,
            new Content.From(
                (long) payload.length,
                io.reactivex.Flowable.fromIterable(chunks)
            ),
            RxNpmProxyStorageTest.MODIFIED,
            RxNpmProxyStorageTest.CONTENT_TYPE
        );
        final NpmAsset teed = this.storage.saveStreamThrough(upstream).blockingGet();
        // Drain the tee'd publisher exactly the way the HTTP response body
        // would.
        final byte[] clientBytes = io.reactivex.Flowable.fromPublisher(teed.dataPublisher())
            .toList()
            .blockingGet()
            .stream()
            .collect(
                java.io.ByteArrayOutputStream::new,
                (out, buf) -> {
                    final byte[] tmp = new byte[buf.remaining()];
                    buf.duplicate().get(tmp);
                    out.write(tmp, 0, tmp.length);
                },
                (a, b) -> a.write(b.toByteArray(), 0, b.size())
            )
            .toByteArray();
        MatcherAssert.assertThat(
            "Client bytes length matches upstream",
            clientBytes.length,
            new IsEqual<>(payload.length)
        );
        org.junit.jupiter.api.Assertions.assertArrayEquals(
            payload, clientBytes,
            "Client bytes must equal upstream bytes"
        );
        // Verify on-disk bytes equal upstream too.
        final byte[] onDisk = this.delegate.value(new Key.From(path)).join().asBytesFuture().join();
        org.junit.jupiter.api.Assertions.assertArrayEquals(
            payload, onDisk,
            "Disk bytes must equal upstream bytes"
        );
    }

    /**
     * Phase 12 — upstream error mid-stream: the client subscriber sees the
     * error and the storage tarball is NOT written (no garbage in cache).
     */
    @Test
    public void streamThroughDoesNotWriteOnUpstreamError() {
        final String path = "broken/-/broken-1.0.0.tgz";
        final NpmAsset upstream = new NpmAsset(
            path,
            new Content.From(
                io.reactivex.Flowable.<java.nio.ByteBuffer>concat(
                    io.reactivex.Flowable.just(java.nio.ByteBuffer.wrap("first-chunk".getBytes(StandardCharsets.UTF_8))),
                    io.reactivex.Flowable.error(new java.io.IOException("upstream blew up"))
                )
            ),
            RxNpmProxyStorageTest.MODIFIED,
            RxNpmProxyStorageTest.CONTENT_TYPE
        );
        final NpmAsset teed = this.storage.saveStreamThrough(upstream).blockingGet();
        final Throwable err = io.reactivex.Flowable.fromPublisher(teed.dataPublisher())
            .ignoreElements()
            .blockingGet();
        org.junit.jupiter.api.Assertions.assertNotNull(err, "Client must observe upstream error");
        MatcherAssert.assertThat(
            "Error message propagates",
            err.getMessage(),
            new IsEqual<>("upstream blew up")
        );
        // Tarball must NOT be in storage (upstream failed before complete).
        MatcherAssert.assertThat(
            "No partial tarball in storage on upstream error",
            this.delegate.exists(new Key.From(path)).join(),
            new IsEqual<>(Boolean.FALSE)
        );
    }

    @BeforeEach
    void setUp() {
        this.delegate = new InMemoryStorage();
        this.storage = new RxNpmProxyStorage(new RxStorageWrapper(this.delegate));
    }

    private String publisherAsStr(final String path) {
        return this.delegate.value(new Key.From(path)).join().asString();
    }

    private void doSavePackage()
        throws IOException {
        this.storage.save(
            new NpmPackage(
                "asdas",
                RxNpmProxyStorageTest.readContent(),
                RxNpmProxyStorageTest.MODIFIED,
                RxNpmProxyStorageTest.REFRESHED
            )
        ).blockingAwait();
    }

    private void doSaveAsset() {
        this.storage.save(
            new NpmAsset(
                "asdas/-/asdas-1.0.0.tgz",
                new Content.From(RxNpmProxyStorageTest.DEF_CONTENT.getBytes()),
                RxNpmProxyStorageTest.MODIFIED,
                RxNpmProxyStorageTest.CONTENT_TYPE
           )
        ).blockingAwait();
    }

    private static String readContent() throws IOException {
        return IOUtils.resourceToString(
            "/json/cached.json",
            StandardCharsets.UTF_8
        );
    }
}
