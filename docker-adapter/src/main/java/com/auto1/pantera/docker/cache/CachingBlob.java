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
package com.auto1.pantera.docker.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.asto.CheckedBlobSource;
import com.auto1.pantera.docker.error.InvalidDigestException;
import com.auto1.pantera.http.log.EcsLogger;
import io.reactivex.Flowable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Blob decorator that streams content to the client while writing to a temp file
 * as a side-effect. On stream completion, the temp file is saved to cache asynchronously.
 * Cache errors never break client pulls (graceful degradation).
 *
 * <p>Race condition note: VertxSliceServer cancels the RxJava subscription after
 * sending all Content-Length bytes to the client. This cancel fires immediately
 * (localhost send is fast) and races with the upstream onComplete signal, which
 * arrives slightly later (last byte from remote). doOnCancel consistently wins
 * for large blobs. The fix: doOnCancel checks whether all expected bytes were
 * already written to the temp file; if so it saves to cache exactly like
 * doOnComplete. An AtomicBoolean guards against double-execution.</p>
 *
 * <p>Cache-store integrity: bytes are re-hashed on save via {@link CheckedBlobSource}
 * (not {@link com.auto1.pantera.docker.asto.TrustedBlobSource} — that stays reserved for
 * hosted-write paths where content is already Pantera-computed and trusted). A truncated
 * or corrupted upstream body therefore fails the cache write instead of being cached and
 * re-served under a digest it does not actually match; the client still receives the
 * tee'd bytes and verifies them independently.</p>
 */
final class CachingBlob implements Blob {

    private final Blob origin;

    private final Layers cache;

    private final String repoName;

    CachingBlob(final Blob origin, final Layers cache, final String repoName) {
        this.origin = origin;
        this.cache = cache;
        this.repoName = repoName;
    }

    CachingBlob(final Blob origin, final Layers cache) {
        this(origin, cache, "unknown");
    }

    @Override
    public Digest digest() {
        return this.origin.digest();
    }

    @Override
    public CompletableFuture<Long> size() {
        return this.origin.size();
    }

    @Override
    public CompletableFuture<Content> content() {
        return this.origin.content().thenApply(content -> {
            final Path tmp;
            final FileChannel ch;
            try {
                tmp = Files.createTempFile("pantera-blob-", ".part");
                tmp.toFile().deleteOnExit();
                ch = FileChannel.open(
                    tmp,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                );
            } catch (final IOException ioe) {
                logWarn("Failed to create temp file, serving uncached", ioe);
                return content;
            }
            final AtomicLong bytes = new AtomicLong(0);
            final AtomicBoolean finished = new AtomicBoolean(false);
            final long expectedSize = content.size().orElse(-1L);
            final Flowable<ByteBuffer> wrapped = Flowable.fromPublisher(content)
                .doOnNext(buf -> {
                    try {
                        bytes.addAndGet(ch.write(buf.asReadOnlyBuffer()));
                    } catch (final IOException ioe) {
                        logWarn("Error writing blob chunk to temp file", ioe);
                    }
                })
                .doOnComplete(() -> {
                    if (finished.compareAndSet(false, true)) {
                        finalizeAndCache(ch, tmp, bytes.get());
                    }
                })
                .doOnCancel(() -> {
                    if (finished.compareAndSet(false, true)) {
                        final long written = bytes.get();
                        if (expectedSize > 0 && written >= expectedSize) {
                            // VertxSliceServer cancelled after sending all Content-Length bytes.
                            // Cancel consistently beats onComplete (localhost send is faster than
                            // remote onComplete). All bytes are in the temp file — save to cache.
                            this.finalizeAndCache(ch, tmp, written);
                        } else {
                            safeClose(ch);
                            safeDelete(tmp);
                        }
                    }
                })
                .doOnError(th -> {
                    if (finished.compareAndSet(false, true)) {
                        safeClose(ch);
                        safeDelete(tmp);
                    }
                });
            return new Content.From(content.size(), wrapped);
        });
    }

    private void finalizeAndCache(final FileChannel ch, final Path tmp, final long size) {
        try {
            ch.force(true);
            ch.close();
            this.saveToCacheAsync(tmp, size);
        } catch (final IOException ioe) {
            safeClose(ch);
            safeDelete(tmp);
            logWarn("Failed to finalize temp file", ioe);
        }
    }

    private void saveToCacheAsync(final Path tmp, final long size) {
        CompletableFuture.runAsync(() -> {
            try {
                final Content fileContent = new Content.From(
                    size, streamFromFile(tmp)
                );
                final Digest digest = this.origin.digest();
                this.cache.put(
                    new CheckedBlobSource(fileContent, digest)
                ).whenComplete((d, ex) -> {
                    safeDelete(tmp);
                    if (ex != null) {
                        if (isDigestMismatch(ex)) {
                            this.logDigestMismatch(digest, ex);
                        } else {
                            logWarn("Failed to save blob to cache", ex);
                        }
                    } else {
                        EcsLogger.info("com.auto1.pantera.docker")
                            .message("Blob cached via streaming")
                            .eventCategory("web")
                            .eventAction("blob_cache")
                            .eventOutcome("success")
                            .field("package.checksum", digest.string())
                            .field("package.size", size)
                            .field("log.source", "application")
                            .log();
                    }
                });
            } catch (final Exception ex) {
                safeDelete(tmp);
                logWarn("Failed to save blob to cache", ex);
            }
        }).exceptionally(err -> {
            safeDelete(tmp);
            logWarn("Unexpected error in cache save", err);
            return null;
        });
    }

    /**
     * Logs the proxy-blob-cache digest-mismatch state transition: a corrupt or
     * truncated upstream body was rejected instead of being cached and re-served
     * under a digest it does not actually match.
     */
    private void logDigestMismatch(final Digest digest, final Throwable ex) {
        EcsLogger.warn("com.auto1.pantera.docker")
            .message("Rejected proxy blob cache write: computed digest did not match declared digest")
            .eventCategory("web")
            .eventAction("blob_digest_mismatch")
            .eventOutcome("failure")
            .field("package.checksum", digest.string())
            .field("repository.name", this.repoName)
            .error(rootCause(ex))
            .field("log.source", "application")
            .log();
    }

    /**
     * Whether the failure chain contains an {@link InvalidDigestException}, i.e. the
     * cache write was rejected because the re-hashed bytes did not match the declared
     * digest, as opposed to an unrelated I/O failure.
     */
    private static boolean isDigestMismatch(final Throwable ex) {
        return rootCause(ex) instanceof InvalidDigestException;
    }

    /**
     * Unwraps nested {@link java.util.concurrent.CompletionException}/{@link
     * java.util.concurrent.ExecutionException} layers to find the originating cause.
     */
    private static Throwable rootCause(final Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null
            && cause.getCause() != cause) { // NOPMD CompareObjectsWithEquals - intentional identity check (cycle guard for self-causing exception)
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * Stream file content in chunks to avoid loading entire blob into heap.
     * Docker image layers can be hundreds of MB; Files.readAllBytes would OOM.
     */
    private static Flowable<ByteBuffer> streamFromFile(final Path path) {
        return Flowable.using(
            () -> FileChannel.open(path, StandardOpenOption.READ),
            channel -> Flowable.generate(emitter -> {
                final ByteBuffer buf = ByteBuffer.allocate(8192);
                final int read = channel.read(buf);
                if (read >= 0) {
                    buf.flip();
                    emitter.onNext(buf);
                } else {
                    emitter.onComplete();
                }
            }),
            FileChannel::close
        );
    }

    private static void safeClose(final FileChannel ch) {
        try {
            if (ch.isOpen()) {
                ch.close();
            }
        } catch (final IOException ex) {
            EcsLogger.debug("com.auto1.pantera.docker")
                .message("Failed to close file channel")
                .error(ex)
                .field("log.source", "application")
                .log();
        }
    }

    private static void safeDelete(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (final IOException ex) {
            EcsLogger.debug("com.auto1.pantera.docker")
                .message("Failed to delete temp file")
                .error(ex)
                .field("log.source", "application")
                .log();
        }
    }

    private static void logWarn(final String msg, final Throwable err) {
        EcsLogger.warn("com.auto1.pantera.docker")
            .message(msg)
            .eventCategory("web")
            .eventAction("blob_cache")
            .eventOutcome("failure")
            .error(err)
            .field("log.source", "application")
            .log();
    }
}
