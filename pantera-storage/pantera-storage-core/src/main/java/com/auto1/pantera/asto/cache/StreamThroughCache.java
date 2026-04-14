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
package com.auto1.pantera.asto.cache;

import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.log.EcsLogger;
import io.reactivex.Flowable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Stream-through cache that delivers remote content to the caller immediately
 * while saving a copy to storage in the background.
 *
 * <p>Unlike {@link FromRemoteCache} which saves content to storage first and then reads it
 * back (two full I/O passes after the network fetch), this implementation tees the stream:
 * each chunk is forwarded to the caller AND written to a temp file. On stream completion,
 * the temp file content is saved to storage asynchronously.</p>
 *
 * <p>Uses NIO temp files instead of ByteArrayOutputStream to avoid heap pressure
 * for large binary artifacts (100MB+). Falls back to in-memory buffering if temp file
 * creation fails.</p>
 *
 * @since 1.20.13
 */
public final class StreamThroughCache implements Cache {

    /**
     * Back-end storage.
     */
    private final Storage storage;

    /**
     * New stream-through cache.
     * @param storage Back-end storage for cache
     */
    public StreamThroughCache(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletionStage<Optional<? extends Content>> load(
        final Key key, final Remote remote, final CacheControl control
    ) {
        return remote.get().handle(
            (content, throwable) -> {
                final CompletionStage<Optional<? extends Content>> res;
                if (throwable == null && content.isPresent()) {
                    res = CompletableFuture.completedFuture(
                        Optional.of(teeContent(key, content.get()))
                    );
                } else {
                    final Throwable error;
                    if (throwable == null) {
                        error = new PanteraIOException(
                            "Failed to load content from remote"
                        );
                    } else {
                        error = throwable;
                    }
                    res = new FromStorageCache(this.storage)
                        .load(key, new Remote.Failed(error), control);
                }
                return res;
            }
        ).thenCompose(Function.identity());
    }

    /**
     * Create a tee-Content that forwards bytes to the caller while writing
     * them to a temp file for background storage save.
     *
     * @param key Storage key for caching
     * @param remote Remote content to tee
     * @return Content that streams to caller and saves to storage
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private Content teeContent(final Key key, final Content remote) {
        final Path tempFile;
        final FileChannel channel;
        try {
            tempFile = Files.createTempFile("pantera-stc-", ".tmp");
            tempFile.toFile().deleteOnExit();
            channel = FileChannel.open(
                tempFile,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (final IOException ex) {
            EcsLogger.debug("com.auto1.pantera.asto.cache")
                .message(String.format("Stream-through: temp file creation failed for key '%s', using in-memory fallback", key.string()))
                .eventCategory("database")
                .eventAction("stream_through")
                .error(ex)
                .log();
            return teeContentInMemory(key, remote);
        }
        final AtomicBoolean saveFired = new AtomicBoolean(false);
        final Flowable<ByteBuffer> teed = Flowable.fromPublisher(remote)
            .doOnNext(buf -> {
                final ByteBuffer copy = buf.asReadOnlyBuffer();
                while (copy.hasRemaining()) {
                    channel.write(copy);
                }
            })
            .doOnComplete(() -> {
                channel.force(true);
                channel.close();
                if (saveFired.compareAndSet(false, true)) {
                    saveFromTempFile(key, tempFile);
                }
            })
            .doOnError(err -> {
                closeQuietly(channel);
                deleteTempFileQuietly(tempFile);
                EcsLogger.debug("com.auto1.pantera.asto.cache")
                    .message(String.format("Stream-through: remote stream error for key '%s', not caching", key.string()))
                    .eventCategory("database")
                    .eventAction("stream_through")
                    .eventOutcome("failure")
                    .error(err)
                    .log();
            });
        return new Content.From(remote.size(), teed);
    }

    /**
     * Fallback: in-memory tee using ByteArrayOutputStream.
     * Used when temp file creation fails (e.g., no temp directory access).
     *
     * @param key Storage key for caching
     * @param remote Remote content to tee
     * @return Content that streams to caller and saves to storage
     */
    private Content teeContentInMemory(final Key key, final Content remote) {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final AtomicBoolean saveFired = new AtomicBoolean(false);
        final Flowable<ByteBuffer> teed = Flowable.fromPublisher(remote)
            .doOnNext(buf -> {
                final ByteBuffer copy = buf.asReadOnlyBuffer();
                final byte[] bytes = new byte[copy.remaining()];
                copy.get(bytes);
                buffer.write(bytes);
            })
            .doOnComplete(() -> {
                if (saveFired.compareAndSet(false, true)) {
                    saveToStorageFromBytes(key, buffer.toByteArray());
                }
            })
            .doOnError(err -> {
                EcsLogger.debug("com.auto1.pantera.asto.cache")
                    .message(String.format("Stream-through: remote stream error for key '%s', not caching (in-memory)", key.string()))
                    .eventCategory("database")
                    .eventAction("stream_through")
                    .eventOutcome("failure")
                    .error(err)
                    .log();
            });
        return new Content.From(remote.size(), teed);
    }

    /**
     * Save content to storage from a temp file, streaming the data through NIO
     * to avoid loading the entire file into heap at once.
     *
     * @param key Storage key
     * @param tempFile Temp file containing the content
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void saveFromTempFile(final Key key, final Path tempFile) {
        try {
            final long size = Files.size(tempFile);
            final Flowable<ByteBuffer> flow = Flowable.using(
                () -> FileChannel.open(tempFile, StandardOpenOption.READ),
                chan -> Flowable.<ByteBuffer>generate(emitter -> {
                    final ByteBuffer buf = ByteBuffer.allocate(65_536);
                    final int read = chan.read(buf);
                    if (read < 0) {
                        emitter.onComplete();
                    } else {
                        buf.flip();
                        emitter.onNext(buf);
                    }
                }),
                FileChannel::close
            );
            final Content content = new Content.From(Optional.of(size), flow);
            this.storage.save(key, content)
                .whenComplete((ignored, err) -> {
                    deleteTempFileQuietly(tempFile);
                    if (err != null) {
                        EcsLogger.warn("com.auto1.pantera.asto.cache")
                            .message(String.format("Stream-through: failed to save to cache from temp file for key '%s'", key.string()))
                            .eventCategory("database")
                            .eventAction("stream_through_save")
                            .eventOutcome("failure")
                            .field("http.response.body.bytes", size)
                            .error(err)
                            .log();
                    } else {
                        EcsLogger.debug("com.auto1.pantera.asto.cache")
                            .message(String.format("Stream-through: saved to cache from temp file for key '%s'", key.string()))
                            .eventCategory("database")
                            .eventAction("stream_through_save")
                            .eventOutcome("success")
                            .field("http.response.body.bytes", size)
                            .log();
                    }
                });
        } catch (final Exception ex) {
            deleteTempFileQuietly(tempFile);
            EcsLogger.warn("com.auto1.pantera.asto.cache")
                .message(String.format("Stream-through: exception initiating save from temp file for key '%s'", key.string()))
                .eventCategory("database")
                .eventAction("stream_through_save")
                .eventOutcome("failure")
                .error(ex)
                .log();
        }
    }

    /**
     * Save content to storage from byte array (in-memory fallback).
     *
     * @param key Storage key
     * @param bytes Content bytes to save
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void saveToStorageFromBytes(final Key key, final byte[] bytes) {
        try {
            this.storage.save(key, new Content.From(bytes))
                .whenComplete((ignored, err) -> {
                    if (err != null) {
                        EcsLogger.warn("com.auto1.pantera.asto.cache")
                            .message(String.format("Stream-through: failed to save to cache for key '%s'", key.string()))
                            .eventCategory("database")
                            .eventAction("stream_through_save")
                            .eventOutcome("failure")
                            .field("http.response.body.bytes", bytes.length)
                            .error(err)
                            .log();
                    } else {
                        EcsLogger.debug("com.auto1.pantera.asto.cache")
                            .message(String.format("Stream-through: saved to cache for key '%s'", key.string()))
                            .eventCategory("database")
                            .eventAction("stream_through_save")
                            .eventOutcome("success")
                            .field("http.response.body.bytes", bytes.length)
                            .log();
                    }
                });
        } catch (final Exception ex) {
            EcsLogger.warn("com.auto1.pantera.asto.cache")
                .message(String.format("Stream-through: exception initiating save for key '%s'", key.string()))
                .eventCategory("database")
                .eventAction("stream_through_save")
                .eventOutcome("failure")
                .error(ex)
                .log();
        }
    }

    /**
     * Close a FileChannel quietly, ignoring errors.
     *
     * @param channel FileChannel to close
     */
    private static void closeQuietly(final FileChannel channel) {
        try {
            if (channel.isOpen()) {
                channel.close();
            }
        } catch (final IOException ex) {
            EcsLogger.debug("com.auto1.pantera.asto.cache")
                .message("Failed to close file channel")
                .error(ex)
                .log();
        }
    }

    /**
     * Delete a temp file quietly, ignoring errors.
     *
     * @param tempFile Path to delete
     */
    private static void deleteTempFileQuietly(final Path tempFile) {
        try {
            Files.deleteIfExists(tempFile);
        } catch (final IOException ex) {
            EcsLogger.debug("com.auto1.pantera.asto.cache")
                .message("Failed to delete temp file")
                .error(ex)
                .log();
        }
    }
}
