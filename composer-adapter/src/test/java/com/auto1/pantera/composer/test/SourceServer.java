/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.composer.test;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.files.FilesSlice;
import com.auto1.pantera.http.slice.LoggingSlice;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.vertx.VertxSliceServer;
import io.vertx.reactivex.core.Vertx;

import java.io.Closeable;
import java.util.Optional;
import java.util.UUID;

/**
 * Source server for obtaining uploaded content by url. For using in test scope.
 * @since 0.4
 */
@SuppressWarnings("PMD.ConstructorOnlyInitializesOrCallOtherConstructors")
public final class SourceServer implements Closeable {
    /**
     * Free port for starting server.
     */
    private final int port;

    /**
     * HTTP server hosting repository.
     */
    private final VertxSliceServer server;

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Ctor.
     * @param vertx Vert.x instance. It should be closed from outside
     * @param port Free port to start server
     */
    public SourceServer(final Vertx vertx, final int port) {
        this.port = port;
        this.storage = new InMemoryStorage();
        this.server = new VertxSliceServer(
            vertx, new LoggingSlice(
            new FilesSlice(
                this.storage, Policy.FREE,
                (username, password) -> Optional.empty(),
                "*", Optional.empty()
            )), port
        );
        this.server.start();
    }

    /**
     * Upload empty ZIP archive as a content.
     * @return Url for obtaining uploaded content.
     * @throws Exception In case of error during uploading
     */
    public String upload() throws Exception {
        return this.upload(new EmptyZip().value());
    }

    /**
     * Upload content.
     * @param content Content for uploading
     * @return Url for obtaining uploaded content.
     */
    public String upload(final byte[] content) {
        final String name = UUID.randomUUID().toString();
        new BlockingStorage(this.storage)
            .save(new Key.From(name), content);
        return String.format("http://host.testcontainers.internal:%d/%s", this.port, name);
    }

    @Override
    public void close() {
        this.server.stop();
    }
}
