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
package com.auto1.pantera.asto.s3;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.blob.BlobStore;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.StorageClass;

/**
 * Proves {@link S3Storage}'s {@link BlobStore} implementation is a thin,
 * correct, single-round-trip-per-call delegate over the underlying S3 client --
 * WS1.0 (spec &sect;I) is additive interface extraction, not a second code path.
 * No Docker/network: an in-memory {@link FakeS3AsyncClient} stands in for S3, and
 * assertions are invocation counts per the "invocation counts, not wall clock"
 * doctrine (CLAUDE.md testing doctrine).
 */
@Timeout(10)
final class S3BlobStoreContractTest {

    @Test
    void existsDelegatesToHeadObjectOnceEachCall() {
        final FakeS3AsyncClient fake = new FakeS3AsyncClient();
        final BlobStore store = S3BlobStoreContractTest.storage(fake, null);
        MatcherAssert.assertThat(
            "missing key reports absent",
            store.exists(new Key.From("a")).join(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat("one HEAD for one exists() call", fake.headCalls(), new IsEqual<>(1));
        store.put(new Key.From("a"), new Content.From("x".getBytes(StandardCharsets.UTF_8))).join();
        MatcherAssert.assertThat(
            "present key reports present",
            store.exists(new Key.From("a")).join(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat("second exists() adds exactly one HEAD", fake.headCalls(), new IsEqual<>(2));
    }

    @Test
    void putThenGetRoundTripsBytesWithOneCallEach() {
        final FakeS3AsyncClient fake = new FakeS3AsyncClient();
        final BlobStore store = S3BlobStoreContractTest.storage(fake, null);
        final byte[] data = "hello blobstore".getBytes(StandardCharsets.UTF_8);
        store.put(new Key.From("k"), new Content.From(data)).join();
        MatcherAssert.assertThat("one PUT for one put() call", fake.putCalls(), new IsEqual<>(1));
        final byte[] got = store.get(new Key.From("k")).join().asBytes();
        MatcherAssert.assertThat(got, new IsEqual<>(data));
        MatcherAssert.assertThat("one GET for one get() call", fake.getCalls(), new IsEqual<>(1));
    }

    @Test
    void headReturnsSizeMetadataWithOneCall() {
        final FakeS3AsyncClient fake = new FakeS3AsyncClient();
        final BlobStore store = S3BlobStoreContractTest.storage(fake, null);
        final byte[] data = "twelve bytes".getBytes(StandardCharsets.UTF_8);
        store.put(new Key.From("k"), new Content.From(data)).join();
        final Meta meta = store.head(new Key.From("k")).join();
        MatcherAssert.assertThat(
            meta.read(Meta.OP_SIZE).get(),
            new IsEqual<>((long) data.length)
        );
        MatcherAssert.assertThat("put() must not itself HEAD", fake.headCalls(), new IsEqual<>(1));
    }

    @Test
    void deleteRemovesKeyWithOneCall() {
        final FakeS3AsyncClient fake = new FakeS3AsyncClient();
        final BlobStore store = S3BlobStoreContractTest.storage(fake, null);
        store.put(new Key.From("k"), new Content.From("x".getBytes(StandardCharsets.UTF_8))).join();
        store.delete(new Key.From("k")).join();
        MatcherAssert.assertThat(fake.deleteCalls(), new IsEqual<>(1));
        MatcherAssert.assertThat(store.exists(new Key.From("k")).join(), new IsEqual<>(false));
    }

    @Test
    void listReturnsMatchingKeysWithOneCall() {
        final FakeS3AsyncClient fake = new FakeS3AsyncClient();
        final BlobStore store = S3BlobStoreContractTest.storage(fake, null);
        store.put(new Key.From("a/1"), new Content.From("x".getBytes(StandardCharsets.UTF_8))).join();
        store.put(new Key.From("a/2"), new Content.From("y".getBytes(StandardCharsets.UTF_8))).join();
        store.put(new Key.From("b/1"), new Content.From("z".getBytes(StandardCharsets.UTF_8))).join();
        final Collection<Key> keys = store.list(new Key.From("a")).join();
        MatcherAssert.assertThat(keys.size(), new IsEqual<>(2));
        MatcherAssert.assertThat("one LIST for one list() call", fake.listCalls(), new IsEqual<>(1));
    }

    @Test
    void putHonorsConfiguredStorageClass() {
        final FakeS3AsyncClient fake = new FakeS3AsyncClient();
        S3BlobStoreContractTest.storage(fake, null)
            .put(new Key.From("std"), new Content.From("x".getBytes(StandardCharsets.UTF_8)))
            .join();
        MatcherAssert.assertThat(
            "no storage-class override -> S3 default (STANDARD)",
            fake.lastStorageClass(),
            new IsNull<>()
        );
        S3BlobStoreContractTest.storage(fake, StorageClass.EXPRESS_ONEZONE)
            .put(new Key.From("exp"), new Content.From("x".getBytes(StandardCharsets.UTF_8)))
            .join();
        MatcherAssert.assertThat(
            fake.lastStorageClass(),
            new IsEqual<>(StorageClass.EXPRESS_ONEZONE)
        );
    }

    /**
     * Builds an {@link S3Storage} (viewed through its {@link BlobStore} facet)
     * directly against a {@link FakeS3AsyncClient}, bypassing the factories so
     * these tests isolate {@link S3Storage}'s own delegation logic.
     *
     * @param fake Fake S3 client.
     * @param storageClass Storage class to configure, or {@code null} for default.
     * @return BlobStore view of a freshly built S3Storage.
     */
    private static BlobStore storage(final FakeS3AsyncClient fake, final StorageClass storageClass) {
        return new S3Storage(
            fake,
            "test-bucket",
            false,
            "http://fake-endpoint",
            32L * 1024 * 1024,
            8 * 1024 * 1024,
            16,
            ChecksumAlgorithm.SHA256,
            null,
            null,
            storageClass,
            false,
            64L * 1024 * 1024,
            8 * 1024 * 1024,
            16,
            null
        );
    }
}
