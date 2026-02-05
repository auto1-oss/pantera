/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.stream;

import com.artipie.http.log.EcsLogger;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Handles stream-through caching using Vert.x native non-blocking I/O.
 *
 * <p>Data flows through a TeeReadStream to both the client response
 * and an AsyncFile cache simultaneously. Backpressure is handled
 * automatically by pausing the upstream when either destination is full.</p>
 *
 * <p>If the cache file cannot be opened or a write error occurs,
 * the handler continues serving the client without caching.</p>
 *
 * @since 1.21.0
 */
public final class CacheStreamHandler {

    /**
     * Logger name for this class.
     */
    private static final String LOGGER_NAME = "com.artipie.http.stream";

    /**
     * Vert.x instance.
     */
    private final Vertx vertx;

    /**
     * Temporary directory for cache files.
     */
    private final Path tempDir;

    /**
     * Constructor.
     *
     * @param vertx Vert.x instance
     * @param tempDir Temporary directory for cache files
     */
    public CacheStreamHandler(final Vertx vertx, final Path tempDir) {
        this.vertx = Objects.requireNonNull(vertx);
        this.tempDir = Objects.requireNonNull(tempDir);
    }

    /**
     * Stream data to client while caching to file.
     *
     * <p>Data is written to both the client WriteStream and a temporary file.
     * When streaming completes, the temporary file is moved to the final cache path.
     * If any cache operation fails, the client still receives all data.</p>
     *
     * @param source Source stream (upstream response)
     * @param cachePath Final cache file path
     * @param client Client response stream
     * @return Future that completes when client streaming is done
     */
    public Future<Void> streamWithCache(
        final ReadStream<Buffer> source,
        final Path cachePath,
        final WriteStream<Buffer> client
    ) {
        final Promise<Void> clientDone = Promise.promise();
        final Path tempFile = this.tempDir.resolve(UUID.randomUUID().toString() + ".tmp");

        this.vertx.fileSystem().open(
            tempFile.toString(),
            new OpenOptions().setWrite(true).setCreate(true).setTruncateExisting(true)
        ).onSuccess(asyncFile -> {
            this.setupTeeStreaming(source, client, asyncFile, tempFile, cachePath, clientDone);
        }).onFailure(err -> {
            // Cache open failed - serve without caching
            EcsLogger.warn(LOGGER_NAME)
                .message("Failed to open cache file, streaming without cache")
                .field("file.path", cachePath.toString())
                .error(err)
                .log();

            // Direct pump to client only
            this.setupDirectStreaming(source, client, clientDone);
        });

        return clientDone.future();
    }

    /**
     * Set up tee streaming to both client and cache file.
     *
     * @param source Source stream
     * @param client Client destination
     * @param asyncFile Cache file
     * @param tempFile Temporary file path
     * @param cachePath Final cache path
     * @param clientDone Promise to complete when client is done
     */
    private void setupTeeStreaming(
        final ReadStream<Buffer> source,
        final WriteStream<Buffer> client,
        final AsyncFile asyncFile,
        final Path tempFile,
        final Path cachePath,
        final Promise<Void> clientDone
    ) {
        final TeeReadStream<Buffer> tee = new TeeReadStream<>(source, client, asyncFile);

        // Handle cache write errors - detach and continue
        asyncFile.exceptionHandler(err -> {
            EcsLogger.warn(LOGGER_NAME)
                .message("Cache write failed, continuing without cache")
                .field("file.path", cachePath.toString())
                .error(err)
                .log();
            tee.detachSecondary();
            this.cleanupTempFile(tempFile);
        });

        // On stream end, finalize cache
        tee.endHandler(v -> {
            clientDone.complete();
            this.finalizeCache(asyncFile, tempFile, cachePath);
        });

        // On error, cleanup and fail
        tee.exceptionHandler(err -> {
            clientDone.fail(err);
            asyncFile.close();
            this.cleanupTempFile(tempFile);
        });

        // Start streaming
        tee.start();
    }

    /**
     * Set up direct streaming to client only (no caching).
     * Implements manual piping with backpressure support.
     *
     * @param source Source stream
     * @param client Client destination
     * @param clientDone Promise to complete when done
     */
    private void setupDirectStreaming(
        final ReadStream<Buffer> source,
        final WriteStream<Buffer> client,
        final Promise<Void> clientDone
    ) {
        // Manual piping with backpressure (replaces deprecated Pump)
        source.handler(data -> {
            client.write(data);
            if (client.writeQueueFull()) {
                source.pause();
                client.drainHandler(v -> source.resume());
            }
        });
        source.endHandler(v -> clientDone.complete());
        source.exceptionHandler(clientDone::fail);
    }

    /**
     * Finalize cache: close file and move to final location.
     *
     * @param asyncFile The async file to close
     * @param tempFile Temporary file path
     * @param finalPath Final cache path
     */
    private void finalizeCache(
        final AsyncFile asyncFile,
        final Path tempFile,
        final Path finalPath
    ) {
        asyncFile.close().onComplete(closed -> {
            // Ensure parent directory exists, then move temp to final
            this.vertx.fileSystem().mkdirs(finalPath.getParent().toString())
                .compose(v -> this.vertx.fileSystem().move(
                    tempFile.toString(),
                    finalPath.toString()
                ))
                .onSuccess(v -> {
                    EcsLogger.debug(LOGGER_NAME)
                        .message("Cache file finalized")
                        .field("file.path", finalPath.toString())
                        .log();
                })
                .onFailure(err -> {
                    EcsLogger.warn(LOGGER_NAME)
                        .message("Failed to finalize cache file")
                        .field("file.path", finalPath.toString())
                        .error(err)
                        .log();
                    this.cleanupTempFile(tempFile);
                });
        });
    }

    /**
     * Cleanup temporary file on error.
     *
     * @param tempFile Temporary file to delete
     */
    private void cleanupTempFile(final Path tempFile) {
        this.vertx.fileSystem().delete(tempFile.toString())
            .onFailure(err -> {
                EcsLogger.debug(LOGGER_NAME)
                    .message("Failed to delete temp file")
                    .field("file.path", tempFile.toString())
                    .log();
            });
    }
}
