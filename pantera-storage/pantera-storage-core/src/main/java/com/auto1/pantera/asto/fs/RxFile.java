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
package com.auto1.pantera.asto.fs;

import com.auto1.pantera.asto.PanteraIOException;
import hu.akarnokd.rxjava2.interop.CompletableInterop;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.subjects.CompletableSubject;
import io.reactivex.subjects.SingleSubject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.cqfn.rio.file.File;

/**
 * The reactive file allows you to perform read and write operations via {@link RxFile#flow()}
 * and {@link RxFile#save(Flowable)} methods respectively.
 * <p>
 * The implementation is based on {@link org.cqfn.rio.file.File} from
 * <a href="https://github.com/cqfn/rio">cqfn/rio</a>.
 *
 * @since 0.12
 */
public class RxFile {

    /**
     * Pool name for metrics identification.
     */
    public static final String POOL_NAME = "pantera.asto.rxfile";

    /**
     * Shared thread factory for all RxFile instances.
     */
    private static final ThreadFactory THREAD_FACTORY = new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger(0);
        @Override
        public Thread newThread(final Runnable runnable) {
            final Thread thread = new Thread(runnable);
            thread.setName(POOL_NAME + ".worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    };

    /**
     * The file location of file system.
     */
    private final Path file;

    /**
     * Thread pool.
     */
    private final ExecutorService exec;

    /**
     * Ctor.
     * @param file The wrapped file
     */
    public RxFile(final Path file) {
        this.file = file;
        this.exec = Executors.newFixedThreadPool(
            Math.max(16, Runtime.getRuntime().availableProcessors() * 4),
            THREAD_FACTORY
        );
    }

    /**
     * Read file content as a flow of bytes.
     * @return A flow of bytes
     */
    public Flowable<ByteBuffer> flow() {
        return Flowable.fromPublisher(new File(this.file).content());
    }

    /**
     * Save a flow of bytes to a file.
     *
     * @param flow The flow of bytes
     * @return Completion or error signal
     */
    public Completable save(final Flowable<ByteBuffer> flow) {
        return Completable.defer(
            () -> CompletableInterop.fromFuture(
                new File(this.file).write(
                    flow,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
            )
        );
    }

    /**
     * Move file to new location.
     *
     * @param target Target path the file is moved to.
     * @return Completion or error signal
     */
    public Completable move(final Path target) {
        return Completable.defer(
            () -> {
                final CompletableSubject res = CompletableSubject.create();
                this.exec.submit(
                    () -> {
                        try {
                            Files.move(this.file, target, StandardCopyOption.REPLACE_EXISTING);
                            res.onComplete();
                        } catch (final IOException iex) {
                            res.onError(new PanteraIOException(iex));
                        }
                    }
                );
                return res;
            }
        );
    }

    /**
     * Delete file.
     *
     * @return Completion or error signal
     */
    public Completable delete() {
        return Completable.defer(
            () -> {
                final CompletableSubject res = CompletableSubject.create();
                this.exec.submit(
                    () -> {
                        try {
                            Files.delete(this.file);
                            res.onComplete();
                        } catch (final IOException iex) {
                            res.onError(new PanteraIOException(iex));
                        }
                    }
                );
                return res;
            }
        );
    }

    /**
     * Get file size.
     *
     * @return File size in bytes.
     */
    public Single<Long> size() {
        return Single.defer(
            () -> {
                final SingleSubject<Long> res = SingleSubject.create();
                this.exec.submit(
                    () -> {
                        try {
                            res.onSuccess(Files.size(this.file));
                        } catch (final IOException iex) {
                            res.onError(new PanteraIOException(iex));
                        }
                    }
                );
                return res;
            }
        );
    }
}
