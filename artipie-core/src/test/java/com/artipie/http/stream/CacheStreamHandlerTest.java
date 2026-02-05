/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.stream;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CacheStreamHandler}.
 */
@ExtendWith(VertxExtension.class)
final class CacheStreamHandlerTest {

    @TempDir
    Path tempDir;

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        this.vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        if (this.vertx != null) {
            this.vertx.close();
        }
    }

    @Test
    void streamsDataToClientAndCache(VertxTestContext ctx) throws Exception {
        final CacheStreamHandler handler = new CacheStreamHandler(vertx, tempDir);
        final Buffer testData = Buffer.buffer("Hello, World!");
        final Path cacheFile = tempDir.resolve("cached.dat");

        // Create a simple source that emits test data
        final FakeAsyncReadStream source = new FakeAsyncReadStream();
        final CollectingWriteStream client = new CollectingWriteStream();

        handler.streamWithCache(source, cacheFile, client)
            .onComplete(ctx.succeeding(v -> {
                // Client received data (verified synchronously)
                ctx.verify(() -> {
                    assertEquals(testData.toString(), client.getCollected().toString());
                });

                // Cache finalization is async, wait a bit then verify
                // This delay allows the async file close + move to complete
                vertx.setTimer(100, timerId -> {
                    ctx.verify(() -> {
                        // Cache file should exist with same data
                        assertTrue(vertx.fileSystem().existsBlocking(cacheFile.toString()),
                            "Cache file should exist at: " + cacheFile);
                        Buffer cached = vertx.fileSystem().readFileBlocking(cacheFile.toString());
                        assertEquals(testData.toString(), cached.toString());
                    });
                    ctx.completeNow();
                });
            }));

        // Emit data after setup (on next tick to ensure handlers are set up)
        vertx.setTimer(10, id -> {
            source.emit(testData);
            source.end();
        });

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void continuesServingClientWhenCacheFails(VertxTestContext ctx) throws Exception {
        final CacheStreamHandler handler = new CacheStreamHandler(vertx, tempDir);
        final Buffer testData = Buffer.buffer("Data for client only");

        // Use invalid path to trigger cache failure
        final Path invalidPath = Path.of("/nonexistent/directory/file.dat");

        final FakeAsyncReadStream source = new FakeAsyncReadStream();
        final CollectingWriteStream client = new CollectingWriteStream();

        handler.streamWithCache(source, invalidPath, client)
            .onComplete(ctx.succeeding(v -> {
                ctx.verify(() -> {
                    // Client still received data despite cache failure
                    assertEquals(testData.toString(), client.getCollected().toString());
                });
                ctx.completeNow();
            }));

        // Emit data after setup
        vertx.setTimer(10, id -> {
            source.emit(testData);
            source.end();
        });

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
    }

    // ---- Fake implementations for testing ----

    /**
     * A fake ReadStream that allows manual data emission for testing.
     */
    static class FakeAsyncReadStream implements ReadStream<Buffer> {
        private Handler<Buffer> dataHandler;
        private Handler<Void> endHandler;
        private Handler<Throwable> exceptionHandler;
        private boolean paused = false;

        @Override
        public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            this.dataHandler = handler;
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            this.paused = true;
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            this.paused = false;
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            return this;
        }

        @Override
        public ReadStream<Buffer> endHandler(Handler<Void> handler) {
            this.endHandler = handler;
            return this;
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            this.exceptionHandler = handler;
            return this;
        }

        void emit(Buffer data) {
            if (dataHandler != null && !paused) {
                dataHandler.handle(data);
            }
        }

        void end() {
            if (endHandler != null) {
                endHandler.handle(null);
            }
        }

        void fail(Throwable err) {
            if (exceptionHandler != null) {
                exceptionHandler.handle(err);
            }
        }

        boolean isPaused() {
            return paused;
        }
    }

    /**
     * A WriteStream that collects all written data for verification.
     */
    static class CollectingWriteStream implements WriteStream<Buffer> {
        private final List<Buffer> collected = new ArrayList<>();
        private Handler<Void> drainHandler;
        private boolean ended = false;

        @Override
        public Future<Void> write(Buffer data) {
            collected.add(data);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> end() {
            this.ended = true;
            return Future.succeededFuture();
        }

        @Override
        public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
            return this;
        }

        @Override
        public boolean writeQueueFull() {
            return false;
        }

        @Override
        public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
            this.drainHandler = handler;
            return this;
        }

        @Override
        public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            return this;
        }

        Buffer getCollected() {
            Buffer result = Buffer.buffer();
            for (Buffer b : collected) {
                result.appendBuffer(b);
            }
            return result;
        }

        boolean isEnded() {
            return ended;
        }
    }
}
