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
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.npm.RandomFreePort;
import com.auto1.pantera.npm.proxy.NpmProxy;
import com.auto1.pantera.vertx.VertxSliceServer;
import io.vertx.core.json.JsonObject;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.ext.web.client.HttpResponse;
import io.vertx.reactivex.ext.web.client.WebClient;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.json.Json;

/**
 * Test cases for {@link DownloadPackageSlice}.
 * @todo #239:30min Fix download meta for empty prefix.
 *  Test for downloading meta hangs for some reason when empty prefix
 *  is passed. It is necessary to find out why it happens and add
 *  empty prefix to params of method DownloadPackageSliceTest#downloadMetaWorks.
 */
final class DownloadPackageSliceTest {

    private static final Vertx VERTX = Vertx.vertx();

    /**
     * Server port.
     */
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        this.port = new RandomFreePort().value();
    }

    @AfterAll
    static void tearDown() {
        DownloadPackageSliceTest.VERTX.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/ctx"})
    void obtainsFromStorage(final String pathprefix) {
        final Storage storage = new InMemoryStorage();
        this.saveFilesToStorage(storage);
        final PackagePath path = new PackagePath(pathprefix.replaceFirst("/", ""));
        try (
            VertxSliceServer server = new VertxSliceServer(
                DownloadPackageSliceTest.VERTX,
                new DownloadPackageSlice(
                    new NpmProxy(
                        storage,
                        new SliceSimple(ResponseBuilder.notFound().build())
                    ),
                    path
                ),
                this.port
            )
        ) {
            this.pereformRequestAndChecks(pathprefix, server);
        }
    }

    /**
     * {@code GET /<pkg>/latest} must return the version manifest pointed at
     * by {@code dist-tags.latest} — not the packument and not a 404. This
     * closes the v1.21.0+ metadata cooldown gap on the dist-tag shortcut
     * endpoint that older yarn/npm versions hit directly.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/ctx"})
    void servesLatestShortcutWithoutCooldown(final String pathprefix) {
        final Storage storage = new InMemoryStorage();
        this.saveFilesToStorage(storage);
        final PackagePath path = new PackagePath(pathprefix.replaceFirst("/", ""));
        try (
            VertxSliceServer server = new VertxSliceServer(
                DownloadPackageSliceTest.VERTX,
                new DownloadPackageSlice(
                    new NpmProxy(
                        storage,
                        new SliceSimple(ResponseBuilder.notFound().build())
                    ),
                    path
                ),
                this.port
            )
        ) {
            server.start();
            final String url = String.format(
                "http://127.0.0.1:%d%s/@hello/simple-npm-project/latest",
                this.port, pathprefix
            );
            final WebClient client = WebClient.create(DownloadPackageSliceTest.VERTX);
            final HttpResponse<Buffer> resp = client.getAbs(url).rxSend().blockingGet();
            MatcherAssert.assertThat(
                "Status code should be 200 OK",
                resp.statusCode(),
                new IsEqual<>(RsStatus.OK.code())
            );
            final JsonObject manifest = resp.body().toJsonObject();
            MatcherAssert.assertThat(
                "Response body should be the version manifest, not the packument",
                manifest.getString("version"),
                new IsEqual<>("1.0.1")
            );
            MatcherAssert.assertThat(
                "Manifest should carry the package name",
                manifest.getString("name"),
                new IsEqual<>("@hello/simple-npm-project")
            );
            // Must NOT be the packument — packument has a 'versions' object.
            MatcherAssert.assertThat(
                "Manifest body must not contain a 'versions' map",
                manifest.containsKey("versions"),
                new IsEqual<>(false)
            );
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/ctx"})
    void obtainsFromRemote(final String pathprefix) {
        final PackagePath path = new PackagePath(pathprefix.replaceFirst("/", ""));
        try (
            VertxSliceServer server = new VertxSliceServer(
                DownloadPackageSliceTest.VERTX,
                new DownloadPackageSlice(
                    new NpmProxy(
                        new InMemoryStorage(),
                        new SliceSimple(
                            ResponseBuilder.ok()
                                .body(new TestResource("storage/@hello/simple-npm-project/meta.json")
                                    .asBytes())
                                .build()
                        )
                    ),
                    path
                ),
                this.port
            )
        ) {
            this.pereformRequestAndChecks(pathprefix, server);
        }
    }

    private void pereformRequestAndChecks(String pathPrefix, VertxSliceServer server) {
        server.start();
        final String url = String.format("http://127.0.0.1:%d%s/@hello/simple-npm-project",
            this.port, pathPrefix);
        final WebClient client = WebClient.create(DownloadPackageSliceTest.VERTX);
        final HttpResponse<Buffer> resp = client.getAbs(url).rxSend().blockingGet();
        MatcherAssert.assertThat(
            "Status code should be 200 OK",
            resp.statusCode(),
            new IsEqual<>(RsStatus.OK.code())
        );
        final JsonObject json = resp.body().toJsonObject();
        MatcherAssert.assertThat(
            "Json response is incorrect",
            json.getJsonObject("versions").getJsonObject("1.0.1")
                .getJsonObject("dist").getString("tarball"),
            new IsEqual<>(
                String.format(
                    "%s/-/@hello/simple-npm-project-1.0.1.tgz",
                    url
                )
            )
        );
    }

    /**
     * Save files to storage from test resources.
     * @param storage Storage
     */
    private void saveFilesToStorage(final Storage storage) {
        final String metajsonpath = "@hello/simple-npm-project/meta.json";
        storage.save(
            new Key.From(metajsonpath),
            new Content.From(
                new TestResource(String.format("storage/%s", metajsonpath)).asBytes()
            )
        ).join();
        storage.save(
            new Key.From("@hello", "simple-npm-project", "meta.meta"),
            new Content.From(
                Json.createObjectBuilder()
                    .add("last-modified", "2020-05-13T16:30:30+01:00")
                    .add("last-refreshed", "2020-05-13T16:30:30+01:00")
                    .build()
                    .toString()
                    .getBytes()
            )
        ).join();
    }
}
