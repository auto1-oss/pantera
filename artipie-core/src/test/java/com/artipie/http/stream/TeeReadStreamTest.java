/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.stream;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TeeReadStream}.
 */
final class TeeReadStreamTest {

    private FakeReadStream source;
    private FakeWriteStream dest1;
    private FakeWriteStream dest2;

    @BeforeEach
    void setUp() {
        this.source = new FakeReadStream();
        this.dest1 = new FakeWriteStream();
        this.dest2 = new FakeWriteStream();
    }

    @Test
    void duplicatesDataToBothDestinations() {
        final TeeReadStream<Buffer> tee = new TeeReadStream<>(source, dest1, dest2);
        tee.start();

        source.emit(Buffer.buffer("chunk1"));
        source.emit(Buffer.buffer("chunk2"));

        assertEquals(2, dest1.received.size());
        assertEquals(2, dest2.received.size());
        assertEquals("chunk1", dest1.received.get(0).toString());
        assertEquals("chunk2", dest2.received.get(1).toString());
    }

    @Test
    void pausesWhenEitherDestinationFull() {
        final TeeReadStream<Buffer> tee = new TeeReadStream<>(source, dest1, dest2);
        tee.start();

        // Fill dest1's queue
        dest1.setWriteQueueFull(true);
        source.emit(Buffer.buffer("data"));

        assertTrue(source.isPaused(), "Source should be paused when dest1 queue full");
    }

    @Test
    void resumesOnlyWhenBothDestinationsReady() {
        final TeeReadStream<Buffer> tee = new TeeReadStream<>(source, dest1, dest2);
        tee.start();

        // Both destinations full
        dest1.setWriteQueueFull(true);
        dest2.setWriteQueueFull(true);
        source.emit(Buffer.buffer("data"));
        assertTrue(source.isPaused());

        // Only dest1 drains - should still be paused
        dest1.setWriteQueueFull(false);
        dest1.triggerDrain();
        assertTrue(source.isPaused(), "Should remain paused until both ready");

        // Now dest2 drains - should resume
        dest2.setWriteQueueFull(false);
        dest2.triggerDrain();
        assertFalse(source.isPaused(), "Should resume when both ready");
    }

    @Test
    void continuesServingClientWhenCacheDetached() {
        final TeeReadStream<Buffer> tee = new TeeReadStream<>(source, dest1, dest2);
        tee.start();

        // Simulate cache error
        tee.detachSecondary();

        // Data should still flow to dest1
        source.emit(Buffer.buffer("after-detach"));

        assertEquals(1, dest1.received.size());
        assertEquals(0, dest2.received.size(), "Detached dest should not receive");
    }

    @Test
    void forwardsEndToHandler() {
        final TeeReadStream<Buffer> tee = new TeeReadStream<>(source, dest1, dest2);
        final AtomicBoolean ended = new AtomicBoolean(false);
        tee.endHandler(v -> ended.set(true));
        tee.start();

        source.end();

        assertTrue(ended.get());
    }

    @Test
    void forwardsExceptionToHandler() {
        final TeeReadStream<Buffer> tee = new TeeReadStream<>(source, dest1, dest2);
        final AtomicBoolean errored = new AtomicBoolean(false);
        tee.exceptionHandler(err -> errored.set(true));
        tee.start();

        source.fail(new RuntimeException("test error"));

        assertTrue(errored.get());
    }

    // ---- Fake implementations for testing ----

    static class FakeReadStream implements ReadStream<Buffer> {
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
            if (dataHandler != null) dataHandler.handle(data);
        }

        void end() {
            if (endHandler != null) endHandler.handle(null);
        }

        void fail(Throwable err) {
            if (exceptionHandler != null) exceptionHandler.handle(err);
        }

        boolean isPaused() {
            return paused;
        }
    }

    static class FakeWriteStream implements WriteStream<Buffer> {
        final List<Buffer> received = new ArrayList<>();
        private Handler<Void> drainHandler;
        private boolean writeQueueFull = false;

        @Override
        public Future<Void> write(Buffer data) {
            received.add(data);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> end() {
            return Future.succeededFuture();
        }

        @Override
        public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
            return this;
        }

        @Override
        public boolean writeQueueFull() {
            return writeQueueFull;
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

        void setWriteQueueFull(boolean full) {
            this.writeQueueFull = full;
        }

        void triggerDrain() {
            if (drainHandler != null) drainHandler.handle(null);
        }
    }
}
