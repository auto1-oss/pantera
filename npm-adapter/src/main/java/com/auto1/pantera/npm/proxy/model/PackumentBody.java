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
package com.auto1.pantera.npm.proxy.model;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.npm.misc.MetadataETag;
import io.reactivex.Flowable;
import io.reactivex.schedulers.Schedulers;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bytes of a packument document as they are stored ({@code meta.json} or
 * {@code meta.abbreviated.json}), with their size and SHA-256.
 *
 * <p>The proxy ingest path produces {@link TempFile} bodies so a packument of
 * tens of megabytes is never held in heap; {@link Bytes} remains for small
 * documents built in memory (tests, the test-only full-package read).</p>
 *
 * @since 2.2.11
 */
public interface PackumentBody {

    /**
     * Size in bytes.
     *
     * @return Size
     */
    long size();

    /**
     * SHA-256 of the bytes, lowercase hex (the {@link MetadataETag} format).
     *
     * @return Hex digest
     */
    String sha256();

    /**
     * Open the bytes for a blocking, sequential read. May be called several
     * times. Never call it on an event loop.
     *
     * @return Fresh stream, owned by the caller
     * @throws IOException When the bytes cannot be read
     */
    InputStream open() throws IOException;

    /**
     * The bytes as reactive content of known size.
     *
     * @return Content
     */
    Content content();

    /**
     * Free what backs the body (delete the temp file). Idempotent; the body
     * must not be read afterwards.
     */
    void release();

    /**
     * In-memory body.
     *
     * @since 2.2.11
     */
    final class Bytes implements PackumentBody {

        /**
         * Document bytes.
         */
        private final byte[] data;

        /**
         * Ctor.
         *
         * @param data Document bytes
         */
        public Bytes(final byte[] data) {
            this.data = data; // NOPMD ArrayIsStoredDirectly - immutable value object; never mutated, a defensive copy would double the document in heap
        }

        @Override
        public long size() {
            return this.data.length;
        }

        @Override
        public String sha256() {
            return new MetadataETag(this.data).calculate();
        }

        @Override
        public InputStream open() {
            return new ByteArrayInputStream(this.data);
        }

        @Override
        public Content content() {
            return new Content.From(this.data);
        }

        @Override
        public void release() {
            // Nothing to free: the bytes are reclaimed with the object.
        }
    }

    /**
     * Body backed by a temporary file that this object owns.
     *
     * @since 2.2.11
     */
    final class TempFile implements PackumentBody {

        /**
         * Read chunk size.
         */
        private static final int CHUNK = 64 * 1024;

        /**
         * The file.
         */
        private final Path path;

        /**
         * File size.
         */
        private final long length;

        /**
         * SHA-256 hex of the file.
         */
        private final String digest;

        /**
         * Executor for blocking file reads and the deletion.
         */
        private final Executor executor;

        /**
         * Whether {@link #release()} ran.
         */
        private final AtomicBoolean released;

        /**
         * Ctor.
         *
         * @param path The file, deleted by {@link #release()}
         * @param length File size
         * @param digest SHA-256 hex of the file
         * @param executor Executor for blocking file reads and the deletion
         */
        public TempFile(
            final Path path, final long length, final String digest, final Executor executor
        ) {
            this.path = path;
            this.length = length;
            this.digest = digest;
            this.executor = executor;
            this.released = new AtomicBoolean();
        }

        /**
         * The backing file.
         *
         * @return Path
         */
        public Path path() {
            return this.path;
        }

        @Override
        public long size() {
            return this.length;
        }

        @Override
        public String sha256() {
            return this.digest;
        }

        @Override
        public InputStream open() throws IOException {
            return Files.newInputStream(this.path);
        }

        @Override
        public Content content() {
            final Path file = this.path;
            return new Content.From(
                this.length,
                Flowable.<ByteBuffer, FileChannel>generate(
                    () -> FileChannel.open(file, StandardOpenOption.READ),
                    (channel, emitter) -> {
                        final ByteBuffer buf = ByteBuffer.allocate(TempFile.CHUNK);
                        int read = 0;
                        while (read == 0) {
                            read = channel.read(buf);
                        }
                        if (read < 0) {
                            emitter.onComplete();
                        } else {
                            buf.flip();
                            emitter.onNext(buf);
                        }
                    },
                    FileChannel::close
                ).subscribeOn(Schedulers.from(this.executor))
            );
        }

        @Override
        public void release() {
            if (this.released.compareAndSet(false, true)) {
                try {
                    this.executor.execute(this::delete);
                } catch (final RejectedExecutionException ex) {
                    this.delete();
                }
            }
        }

        /**
         * Delete the file, logging (never throwing) on failure.
         */
        private void delete() {
            try {
                Files.deleteIfExists(this.path);
            } catch (final IOException ex) {
                EcsLogger.warn("com.auto1.pantera.npm")
                    .message("Failed to delete packument temp file")
                    .eventCategory("file")
                    .eventAction("packument_temp_delete")
                    .eventOutcome("failure")
                    .field("file.path", this.path.toString())
                    .error(ex)
                    .field("log.source", "application")
                    .log();
            }
        }
    }
}
