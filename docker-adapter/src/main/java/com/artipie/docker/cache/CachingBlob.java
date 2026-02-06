/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.docker.cache;

import com.artipie.asto.Content;
import com.artipie.docker.Blob;
import com.artipie.docker.Digest;
import com.artipie.docker.Layers;
import com.artipie.docker.asto.TrustedBlobSource;
import com.artipie.http.log.EcsLogger;
import io.reactivex.Flowable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Blob decorator that streams content to the client while writing to a temp file
 * as a side-effect. On stream completion, the temp file is saved to cache asynchronously.
 * Cache errors never break client pulls (graceful degradation).
 */
final class CachingBlob implements Blob {

    private final Blob origin;

    private final Layers cache;

    CachingBlob(final Blob origin, final Layers cache) {
        this.origin = origin;
        this.cache = cache;
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
                tmp = Files.createTempFile("artipie-blob-", ".part");
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
            final Flowable<ByteBuffer> wrapped = Flowable.fromPublisher(content)
                .doOnNext(buf -> {
                    try {
                        bytes.addAndGet(ch.write(buf.asReadOnlyBuffer()));
                    } catch (final IOException ioe) {
                        logWarn("Error writing blob chunk to temp file", ioe);
                    }
                })
                .doOnComplete(() -> {
                    try {
                        ch.force(true);
                        ch.close();
                        this.saveToCacheAsync(tmp, bytes.get());
                    } catch (final IOException ioe) {
                        safeClose(ch);
                        safeDelete(tmp);
                        logWarn("Failed to finalize temp file", ioe);
                    }
                })
                .doOnError(th -> {
                    safeClose(ch);
                    safeDelete(tmp);
                });
            return new Content.From(content.size(), wrapped);
        });
    }

    private void saveToCacheAsync(final Path tmp, final long size) {
        CompletableFuture.runAsync(() -> {
            try {
                final byte[] data = Files.readAllBytes(tmp);
                this.cache.put(
                    new TrustedBlobSource(new Content.From(data), this.origin.digest())
                ).whenComplete((d, ex) -> {
                    safeDelete(tmp);
                    if (ex != null) {
                        logWarn("Failed to save blob to cache", ex);
                    } else {
                        EcsLogger.debug("com.artipie.docker")
                            .message("Blob cached via streaming")
                            .eventCategory("repository")
                            .eventAction("blob_cache")
                            .eventOutcome("success")
                            .field("package.checksum", this.origin.digest().string())
                            .field("package.size", size)
                            .log();
                    }
                });
            } catch (final IOException ioe) {
                safeDelete(tmp);
                logWarn("Failed to read temp file for cache", ioe);
            }
        });
    }

    private static void safeClose(final FileChannel ch) {
        try {
            if (ch.isOpen()) {
                ch.close();
            }
        } catch (final IOException ex) {
            EcsLogger.debug("com.artipie.docker")
                .message("Failed to close file channel")
                .error(ex)
                .log();
        }
    }

    private static void safeDelete(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (final IOException ex) {
            EcsLogger.debug("com.artipie.docker")
                .message("Failed to delete temp file")
                .error(ex)
                .log();
        }
    }

    private static void logWarn(final String msg, final Throwable err) {
        EcsLogger.warn("com.artipie.docker")
            .message(msg)
            .eventCategory("repository")
            .eventAction("blob_cache")
            .eventOutcome("failure")
            .error(err)
            .log();
    }
}
