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
package com.auto1.pantera.asto.streams;

import com.auto1.pantera.asto.PanteraIOException;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.cqfn.rio.WriteGreed;
import org.cqfn.rio.stream.ReactiveOutputStream;
import org.reactivestreams.Publisher;

/**
 * Process content as input stream.
 * This class allows to treat storage item as input stream and
 * perform some action with this stream (read/uncompress/parse etc).
 * @param <T> Result type
 * @since 1.4
 */
public final class ContentAsStream<T> {

    /**
     * Pool name for metrics identification.
     */
    public static final String POOL_NAME = "pantera.io.stream";

    /**
     * Dedicated executor for blocking stream operations.
     * CRITICAL: Without this, CompletableFuture.supplyAsync() uses ForkJoinPool.commonPool()
     * which can block Vert.x event loop threads, causing "Thread blocked" warnings.
     */
    private static final ExecutorService BLOCKING_EXECUTOR = Executors.newFixedThreadPool(
        Math.max(16, Runtime.getRuntime().availableProcessors() * 4),
        new ThreadFactoryBuilder()
            .setNameFormat(POOL_NAME + ".worker-%d")
            .setDaemon(true)
            .build()
    );

    /**
     * Publisher to process.
     */
    private final Publisher<ByteBuffer> content;

    /**
     * Ctor.
     * @param content Content
     */
    public ContentAsStream(final Publisher<ByteBuffer> content) {
        this.content = content;
    }

    /**
     * Process storage item as input stream by performing provided action on it.
     * @param action Action to perform
     * @return Completion action with the result
     */
    public CompletionStage<T> process(final Function<InputStream, T> action) {
        // CRITICAL: Use dedicated executor to avoid blocking Vert.x event loop
        return CompletableFuture.supplyAsync(
            () -> {
                try (
                    PipedInputStream in = new PipedInputStream();
                    PipedOutputStream out = new PipedOutputStream(in)
                ) {
                    final CompletionStage<Void> ros =
                        new ReactiveOutputStream(out).write(this.content, WriteGreed.SYSTEM);
                    final T result = action.apply(in);
                    return ros.thenApply(nothing -> result);
                } catch (final IOException err) {
                    throw new PanteraIOException(err);
                }
            },
            BLOCKING_EXECUTOR
        ).thenCompose(Function.identity());
    }
}
