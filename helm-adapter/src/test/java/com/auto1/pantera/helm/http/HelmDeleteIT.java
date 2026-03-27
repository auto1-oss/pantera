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
package com.auto1.pantera.helm.http;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.helm.test.ContentOfIndex;
import com.auto1.pantera.http.misc.RandomFreePort;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.slice.LoggingSlice;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.vertx.VertxSliceServer;
import io.vertx.reactivex.core.Vertx;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

/**
 * IT for remove operation.
 */
final class HelmDeleteIT {
    /**
     * Vert instance.
     */
    private static final Vertx VERTX = Vertx.vertx();

    /**
     * The server.
     */
    private VertxSliceServer server;

    /**
     * Port.
     */
    private int port;

    /**
     * URL connection.
     */
    private HttpURLConnection conn;

    /**
     * Storage.
     */
    private Storage storage;

    /**
     * Artifact events.
     */
    private Queue<ArtifactEvent> events;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.events = new ConcurrentLinkedQueue<>();
        this.port = RandomFreePort.get();
        this.server = new VertxSliceServer(
            HelmDeleteIT.VERTX,
            new LoggingSlice(
                new HelmSlice(
                    this.storage, String.format("http://localhost:%d", this.port),
                    Policy.FREE, (username, password) -> Optional.empty(),
                    "*", Optional.of(this.events)
                )
            ),
            this.port
        );
        this.server.start();
    }

    @AfterAll
    static void tearDownAll() {
        HelmDeleteIT.VERTX.close();
    }

    @AfterEach
    void tearDown() {
        this.conn.disconnect();
        this.server.close();
    }

    @Test
    void chartShouldBeDeleted() throws Exception {
        Stream.of("index.yaml", "ark-1.0.1.tgz", "ark-1.2.0.tgz", "tomcat-0.4.1.tgz")
            .forEach(source -> new TestResource(source).saveTo(this.storage));
        this.conn = (HttpURLConnection) URI.create(
            String.format("http://localhost:%d/charts/tomcat", this.port)
        ).toURL().openConnection();
        this.conn.setRequestMethod(RqMethod.DELETE.value());
        this.conn.setDoOutput(true);
        MatcherAssert.assertThat(
            "Response status is not 200",
            this.conn.getResponseCode(),
            new IsEqual<>(RsStatus.OK.code())
        );
        MatcherAssert.assertThat(
            "Archive was not deleted",
            this.storage.exists(new Key.From("tomcat-0.4.1.tgz")).join(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "Index was not updated",
            new ContentOfIndex(this.storage).index().byChart("tomcat").isEmpty(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat("One item was added into events queue", this.events.size() == 1);
    }
}
