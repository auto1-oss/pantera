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

import com.auto1.pantera.npm.proxy.json.TarballRefRewriter;
import com.auto1.pantera.npm.proxy.model.PackumentBody;
import com.auto1.pantera.npm.proxy.model.PackumentSink;
import io.reactivex.Flowable;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import org.reactivestreams.Publisher;

/**
 * Streams an upstream packument body into a temp file in its cached form.
 *
 * <p>The body flows chunk by chunk through {@link TarballRefRewriter} (the
 * streaming twin of {@code CachedContent}) into a {@link PackumentSink}
 * that hashes as it writes; the result is a {@link PackumentBody.TempFile}
 * with the exact bytes, size and SHA-256 of what becomes {@code meta.json}.
 * Nothing proportional to the document is held in heap: demand on the
 * upstream is bounded by {@link #PREFETCH} chunks.</p>
 *
 * <p>The file writes run on the given executor (never on the thread that
 * delivers upstream bytes, which may be an event loop). The temp file is
 * deleted when the upstream fails, the rewrite fails or the subscriber
 * disposes; on success its ownership passes to the returned body.</p>
 *
 * @since 2.2.11
 */
final class PackumentIngest {

    /**
     * Upstream chunks requested ahead of the file writes.
     */
    private static final int PREFETCH = 16;

    /**
     * Package name (for the tarball rewrite).
     */
    private final String name;

    /**
     * Executor for the blocking file writes and the produced body.
     */
    private final Executor executor;

    /**
     * Directory for temp files.
     */
    private final Path tmp;

    /**
     * Ctor.
     *
     * @param name Package name
     * @param executor Executor for blocking file I/O
     * @param tmp Directory for temp files
     */
    PackumentIngest(final String name, final Executor executor, final Path tmp) {
        this.name = name;
        this.executor = executor;
        this.tmp = tmp;
    }

    /**
     * Ingest an upstream body.
     *
     * @param upstream Upstream packument bytes
     * @return Body of the cached packument, owned by the subscriber
     */
    Single<PackumentBody> ingest(final Publisher<ByteBuffer> upstream) {
        final Scheduler scheduler = Schedulers.from(this.executor);
        return Single.using(
            () -> new PackumentSink(this.tmp, this.executor),
            sink -> {
                final TarballRefRewriter rewriter = new TarballRefRewriter(this.name);
                return Flowable.fromPublisher(upstream)
                    .observeOn(scheduler, false, PackumentIngest.PREFETCH)
                    .doOnNext(chunk -> rewriter.write(chunk, sink))
                    .ignoreElements()
                    .andThen(
                        Single.<PackumentBody>fromCallable(
                            () -> {
                                rewriter.finish(sink);
                                return sink.commit();
                            }
                        )
                    );
            },
            PackumentSink::abort
        ).subscribeOn(scheduler);
    }
}
