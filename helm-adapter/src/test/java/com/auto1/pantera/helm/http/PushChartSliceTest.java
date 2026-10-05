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
package com.auto1.pantera.helm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.helm.test.ContentOfIndex;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.index.SyncArtifactIndexer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.codec.digest.DigestUtils;
import org.cactoos.list.ListOf;
import org.cactoos.set.SetOf;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link PushChartSlice}.
 * @since 0.4
 */
final class PushChartSliceTest {

    /**
     * Storage for tests.
     */
    private Storage storage;

    /**
     * Artifact events.
     */
    private Queue<ArtifactEvent> events;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.events = new ConcurrentLinkedQueue<>();
    }

    @Test
    void shouldNotUpdateAfterUpload() {
        final String tgz = "ark-1.0.1.tgz";
        MatcherAssert.assertThat(
            "Wrong status, expected OK",
            new PushChartSlice(this.storage, Optional.of(this.events), "my-helm"),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(RqMethod.GET, "/?updateIndex=false"),
                Headers.EMPTY,
                new Content.From(new TestResource(tgz).asBytes())
            )
        );
        MatcherAssert.assertThat(
            "Index was generated",
            this.storage.list(Key.ROOT).join(),
            new IsEqual<>(new ListOf<Key>(new Key.From("ark", tgz)))
        );
        MatcherAssert.assertThat("No events were added to queue", this.events.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/?updateIndex=true", "/"})
    void shouldUpdateIndexAfterUpload(final String uri) {
        final String tgz = "ark-1.0.1.tgz";
        MatcherAssert.assertThat(
            "Wrong status, expected OK",
            new PushChartSlice(this.storage, Optional.of(this.events), "test-helm"),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(RqMethod.GET, uri),
                Headers.from(
                    new com.auto1.pantera.http.headers.Header(com.auto1.pantera.http.slice.EcsLoggingSlice.CTX_TRACE_ID_HEADER, "trace-helm"),
                    new com.auto1.pantera.http.headers.Header(com.auto1.pantera.http.slice.EcsLoggingSlice.CTX_CLIENT_IP_HEADER, "10.0.0.1")
                ),
                new Content.From(new TestResource(tgz).asBytes())
            )
        );
        MatcherAssert.assertThat(
            "Index was not updated",
            new ContentOfIndex(this.storage).index()
                .entries().keySet(),
            new IsEqual<>(new SetOf<>("ark"))
        );
        MatcherAssert.assertThat("One event was added to queue", this.events.size() == 1);
        MatcherAssert.assertThat(
            "B36: the publish event carries the request trace.id",
            this.events.peek().traceId(), new org.hamcrest.core.IsEqual<>("trace-helm")
        );
        MatcherAssert.assertThat(
            "B36: the publish event carries the request client.ip",
            this.events.peek().clientIp(), new org.hamcrest.core.IsEqual<>("10.0.0.1")
        );
    }

    @Test
    void immutableRefusesRepushWithDifferentBytes() throws IOException {
        final byte[] original = new TestResource("ark-1.0.1.tgz").asBytes();
        final byte[] changed = PushChartSliceTest.regzipped(original);
        MatcherAssert.assertThat(
            "Precondition: the re-push carries different bytes",
            Arrays.equals(original, changed), new IsEqual<>(false)
        );
        final PushChartSlice slice = new PushChartSlice(
            this.storage, Optional.of(this.events), "my-helm", SyncArtifactIndexer.NOOP, true
        );
        MatcherAssert.assertThat(
            "First push is accepted",
            slice.response(
                new RequestLine(RqMethod.PUT, "/"), Headers.EMPTY, new Content.From(original)
            ).join().status(),
            new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "Re-push of the same name+version is refused with 409",
            slice.response(
                new RequestLine(RqMethod.PUT, "/"), Headers.EMPTY, new Content.From(changed)
            ).join().status(),
            new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "Stored archive keeps the first push's bytes",
            Arrays.equals(
                this.storage.value(new Key.From("ark", "ark-1.0.1.tgz")).join().asBytes(),
                original
            ),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "index.yaml keeps the first push's digest",
            new ContentOfIndex(this.storage).index().byChart("ark").get(0).get("digest"),
            new IsEqual<>(DigestUtils.sha256Hex(original))
        );
        MatcherAssert.assertThat(
            "Only the first push produced an artifact event",
            this.events.size(), new IsEqual<>(1)
        );
    }

    @Test
    void immutableRefusesIdenticalRepush() {
        final byte[] original = new TestResource("ark-1.0.1.tgz").asBytes();
        final PushChartSlice slice = new PushChartSlice(
            this.storage, Optional.of(this.events), "my-helm", SyncArtifactIndexer.NOOP, true
        );
        slice.response(
            new RequestLine(RqMethod.PUT, "/"), Headers.EMPTY, new Content.From(original)
        ).join();
        MatcherAssert.assertThat(
            slice,
            new SliceHasResponse(
                new RsHasStatus(RsStatus.CONFLICT),
                new RequestLine(RqMethod.PUT, "/"),
                Headers.EMPTY,
                new Content.From(original)
            )
        );
    }

    @Test
    void mutableOverwritesArchiveAndIndexEntry() throws IOException {
        final byte[] original = new TestResource("ark-1.0.1.tgz").asBytes();
        final byte[] changed = PushChartSliceTest.regzipped(original);
        final PushChartSlice slice = new PushChartSlice(
            this.storage, Optional.of(this.events), "my-helm", SyncArtifactIndexer.NOOP, false
        );
        slice.response(
            new RequestLine(RqMethod.PUT, "/"), Headers.EMPTY, new Content.From(original)
        ).join();
        MatcherAssert.assertThat(
            "Re-push of the same name+version is accepted",
            slice.response(
                new RequestLine(RqMethod.PUT, "/"), Headers.EMPTY, new Content.From(changed)
            ).join().status(),
            new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "Stored archive holds the re-pushed bytes",
            Arrays.equals(
                this.storage.value(new Key.From("ark", "ark-1.0.1.tgz")).join().asBytes(),
                changed
            ),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "index.yaml still lists the version once",
            new ContentOfIndex(this.storage).index().byChart("ark").size(),
            new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "index.yaml digest describes the re-pushed archive",
            new ContentOfIndex(this.storage).index().byChart("ark").get(0).get("digest"),
            new IsEqual<>(DigestUtils.sha256Hex(changed))
        );
    }

    @Test
    void legacyCtorOverwrites() {
        final byte[] original = new TestResource("ark-1.0.1.tgz").asBytes();
        final PushChartSlice slice = new PushChartSlice(
            this.storage, Optional.of(this.events), "my-helm"
        );
        slice.response(
            new RequestLine(RqMethod.PUT, "/"), Headers.EMPTY, new Content.From(original)
        ).join();
        MatcherAssert.assertThat(
            slice,
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(RqMethod.PUT, "/"),
                Headers.EMPTY,
                new Content.From(original)
            )
        );
    }

    /**
     * Same archive content re-compressed: same Chart.yaml, different bytes.
     * @param tgz Gzipped tarball
     * @return Re-gzipped tarball
     * @throws IOException On error
     */
    private static byte[] regzipped(final byte[] tgz) throws IOException {
        final byte[] tar;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(tgz))) {
            tar = in.readAllBytes();
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(tar);
        }
        return out.toByteArray();
    }
}
