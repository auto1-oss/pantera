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

import com.auto1.pantera.http.log.EcsLogger;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.Executor;

/**
 * Write-once temporary file that computes the SHA-256 and size of what is
 * written, then hands the file over as a {@link PackumentBody.TempFile}.
 *
 * <p>Blocking: use it from a worker thread, never an event loop. Writes and
 * {@link #abort()} are serialised, so an abort racing an in-flight write (a
 * cancelled ingest) never closes the file under it.</p>
 *
 * @since 2.2.11
 */
public final class PackumentSink extends OutputStream {

    /**
     * Write buffer size.
     */
    private static final int BUFFER = 64 * 1024;

    /**
     * The temp file.
     */
    private final Path path;

    /**
     * Buffered stream into the file that keeps a running SHA-256 of what is
     * written (accessed only under this sink's monitor).
     */
    private final DigestOutputStream out;

    /**
     * Executor the committed body reads and deletes on.
     */
    private final Executor executor;

    /**
     * Whether the file was committed or aborted.
     */
    private boolean done;

    /**
     * Ctor. Creates the temp file.
     *
     * @param dir Directory for the temp file
     * @param executor Executor the committed body reads and deletes on
     * @throws IOException When the file cannot be created
     */
    public PackumentSink(final Path dir, final Executor executor) throws IOException {
        this.path = Files.createTempFile(dir, "pantera-npm-packument-", ".json");
        this.executor = executor;
        try {
            this.out = new DigestOutputStream(
                new BufferedOutputStream(Files.newOutputStream(this.path), PackumentSink.BUFFER),
                PackumentSink.sha256()
            );
        } catch (final IOException ex) {
            Files.deleteIfExists(this.path);
            throw ex;
        }
    }

    @Override
    public synchronized void write(final int value) throws IOException {
        this.ensureOpen();
        this.out.write(value);
    }

    @Override
    public synchronized void write(
        final byte[] bytes, final int off, final int len
    ) throws IOException {
        this.ensureOpen();
        this.out.write(bytes, off, len);
    }

    /**
     * Close the file and hand it over. The sink no longer owns the file.
     *
     * @return Body backed by the file
     * @throws IOException When the file cannot be flushed
     */
    public synchronized PackumentBody.TempFile commit() throws IOException {
        this.ensureOpen();
        try {
            this.out.close();
            final long size = Files.size(this.path);
            this.done = true;
            return new PackumentBody.TempFile(
                this.path, size, HexFormat.of().formatHex(this.out.getMessageDigest().digest()), this.executor
            );
        } catch (final IOException ex) {
            this.abort();
            throw ex;
        }
    }

    /**
     * Discard the file unless it was committed. Idempotent, never throws.
     */
    public synchronized void abort() {
        if (!this.done) {
            this.done = true;
            try {
                this.out.close();
            } catch (final IOException ex) {
                PackumentSink.warn("Failed to close aborted packument temp file", this.path, ex);
            }
            try {
                Files.deleteIfExists(this.path);
            } catch (final IOException ex) {
                PackumentSink.warn("Failed to delete aborted packument temp file", this.path, ex);
            }
        }
    }

    /**
     * {@link OutputStream#close()} of a sink aborts it unless committed, so
     * try-with-resources never leaks the file.
     */
    @Override
    public void close() {
        this.abort();
    }

    /**
     * Fail a write after commit/abort.
     *
     * @throws IOException Always when done
     */
    private void ensureOpen() throws IOException {
        if (this.done) {
            throw new IOException("Packument temp file already committed or aborted");
        }
    }

    /**
     * New SHA-256 digest.
     *
     * @return Digest
     */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    /**
     * Log a cleanup failure.
     *
     * @param message Message
     * @param path File
     * @param err Failure
     */
    private static void warn(final String message, final Path path, final Throwable err) {
        EcsLogger.warn("com.auto1.pantera.npm")
            .message(message)
            .eventCategory("file")
            .eventAction("packument_temp_abort")
            .eventOutcome("failure")
            .field("file.path", path.toString())
            .error(err)
            .field("log.source", "application")
            .log();
    }
}
