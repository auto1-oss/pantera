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
import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.rx.RxStorageWrapper;
import com.auto1.pantera.helm.ChartYaml;
import com.auto1.pantera.helm.TgzArchive;
import com.auto1.pantera.helm.metadata.IndexYaml;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqParams;
import com.auto1.pantera.scheduling.ArtifactEvent;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import org.reactivestreams.Publisher;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

/**
 * A Slice which accept archived charts, save them into a storage and trigger index.yml reindexing.
 * By default, it updates index file after uploading.
 */
final class PushChartSlice implements Slice {

    /**
     * Repository type.
     */
    static final String REPO_TYPE = "helm";

    /**
     * Default cap on an uploaded chart archive (128 MiB). Before 2.2.9 the
     * whole request body was collected into one contiguous array with no
     * bound at all, so an authenticated writer could push an arbitrarily
     * large "chart" straight into the heap (resource-dos F45). Real charts
     * are KBs to a few MBs; the cap leaves ample room for CRD-heavy ones.
     */
    static final long DEFAULT_MAX_CHART_BYTES = 128L * 1024L * 1024L;

    /**
     * The Storage.
     */
    private final Storage storage;

    /**
     * Events queue.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String rname;

    /** Synchronous artifact-index writer for read-after-write consistency. */
    private final com.auto1.pantera.index.SyncArtifactIndexer syncIndex;

    /**
     * Cap on an uploaded chart archive, in bytes.
     */
    private final long maxChartBytes;

    /**
     * Legacy ctor (no synchronous index writer).
     * @param storage The storage.
     * @param events Events queue
     * @param rname Repository name
     */
    PushChartSlice(final Storage storage, final Optional<Queue<ArtifactEvent>> events,
        final String rname) {
        this(storage, events, rname, com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with an explicit chart cap and no synchronous index writer (tests).
     * @param storage The storage.
     * @param events Events queue
     * @param rname Repository name
     * @param maxChartBytes Cap on an uploaded chart archive, in bytes
     */
    PushChartSlice(final Storage storage, final Optional<Queue<ArtifactEvent>> events,
        final String rname, final long maxChartBytes) {
        this(
            storage, events, rname,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP, maxChartBytes
        );
    }

    /**
     * Ctor with synchronous index writer.
     * @param storage The storage.
     * @param events Events queue
     * @param rname Repository name
     * @param syncIndex Synchronous artifact-index writer
     */
    PushChartSlice(final Storage storage, final Optional<Queue<ArtifactEvent>> events,
        final String rname,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex) {
        this(storage, events, rname, syncIndex, PushChartSlice.DEFAULT_MAX_CHART_BYTES);
    }

    /**
     * Canonical ctor.
     * @param storage The storage.
     * @param events Events queue
     * @param rname Repository name
     * @param syncIndex Synchronous artifact-index writer
     * @param maxChartBytes Cap on an uploaded chart archive, in bytes
     */
    PushChartSlice(final Storage storage, final Optional<Queue<ArtifactEvent>> events,
        final String rname,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
        final long maxChartBytes) {
        this.storage = storage;
        this.events = events;
        this.rname = rname;
        this.syncIndex = syncIndex;
        this.maxChartBytes = maxChartBytes;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final Optional<String> upd = new RqParams(line.uri()).value("updateIndex");
        // Meter the upload against the chart cap: the body used to be collected
        // whole with no bound (resource-dos F45). A metered overflow surfaces
        // as RequestBodyTooLargeException -> 413 below.
        final Content bounded = new com.auto1.pantera.http.body.BoundedContent(
            body, this.maxChartBytes
        );
        return memory(bounded).flatMapCompletable(
                tgz -> {
                    // Organize by chart name: <chart_name>/<chart_name>-<version>.tgz
                    final ChartYaml chart = tgz.chartYaml();
                    final Key artifactKey = new Key.From(chart.name(), tgz.name());
                    return new RxStorageWrapper(this.storage).save(
                        artifactKey,
                        new Content.From(tgz.bytes())
                    ).andThen(
                        Completable.defer(
                            () -> {
                                final Completable res;
                                if (upd.isEmpty() || "true".equals(upd.get())) {
                                    final ArtifactEvent event = new ArtifactEvent(
                                        PushChartSlice.REPO_TYPE, this.rname,
                                        new Login(headers).getValue(),
                                        chart.name(), chart.version(), tgz.size(),
                                        System.currentTimeMillis(), null,
                                        artifactKey.string()
                                    );
                                    this.events.ifPresent(queue -> queue.add(event));
                                    com.auto1.pantera.http.cache.NegativeCacheRegistry.instance()
                                        .invalidateAfterUpload("helm", chart.name());
                                    com.auto1.pantera.cooldown.metadata
                                        .FilteredMetadataCacheRegistry.instance()
                                        .invalidateAfterUpload("helm", chart.name());
                                    res = new IndexYaml(this.storage).update(tgz)
                                        .andThen(Completable.create(emitter ->
                                            this.syncIndex.recordSync(event)
                                                .whenComplete((v, err) -> {
                                                    if (err == null) {
                                                        emitter.onComplete();
                                                    } else {
                                                        emitter.onError(err);
                                                    }
                                                })
                                        ));
                                } else {
                                    res = Completable.complete();
                                }
                                return res;
                            }
                        )
                    );
                }
            ).andThen(Single.just(ResponseBuilder.ok().build()))
            .to(SingleInterop.get())
            .toCompletableFuture()
            .handle((response, error) -> {
                if (error == null) {
                    return response;
                }
                if (com.auto1.pantera.http.RequestBodyTooLargeException.isCause(error)) {
                    return ResponseBuilder.payloadTooLarge().build();
                }
                if (error instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new java.util.concurrent.CompletionException(error);
            });
    }

    /**
     * Convert buffers into a byte array.
     * @param bufs The list of buffers.
     * @return The byte array.
     */
    static byte[] bufsToByteArr(final List<ByteBuffer> bufs) {
        final Integer size = bufs.stream()
            .map(Buffer::remaining)
            .reduce(Integer::sum)
            .orElse(0);
        final byte[] bytes = new byte[size];
        int pos = 0;
        for (final ByteBuffer buf : bufs) {
            final byte[] tocopy = new Remaining(buf).bytes();
            System.arraycopy(tocopy, 0, bytes, pos, tocopy.length);
            pos += tocopy.length;
        }
        return bytes;
    }

    /**
     * Loads bytes into the memory.
     * @param body The body.
     * @return Bytes in a single byte array
     */
    private static Single<TgzArchive> memory(final Publisher<ByteBuffer> body) {
        return Flowable.fromPublisher(body)
            .toList()
            .map(bufs -> new TgzArchive(bufsToByteArr(bufs)));
    }
}
