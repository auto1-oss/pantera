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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.slice.TrimPathSlice;
import com.auto1.pantera.vertx.VertxSliceServer;
import io.vertx.core.json.JsonObject;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.ext.web.client.WebClient;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.io.IOUtils;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests Download Package Slice works.
 * @since 0.6
 */
public class DownloadPackageSliceTest {
    @Test
    public void downloadMetaWorks() throws IOException, ExecutionException, InterruptedException {
        final Vertx vertx = Vertx.vertx();
        final Storage storage = new InMemoryStorage();
        storage.save(
            new Key.From("@hello", "simple-npm-project", "meta.json"),
            new Content.From(
                IOUtils.resourceToByteArray("/storage/@hello/simple-npm-project/meta.json")
            )
        ).get();
        // Bind to an ephemeral port and read the real one back from start():
        // probing a free port and binding later races other tests (-T8). The
        // slice needs the port for its base URL, so it is set once bound.
        final AtomicReference<Slice> target = new AtomicReference<>();
        final VertxSliceServer server = new VertxSliceServer(
            vertx,
            (line, headers, body) -> target.get().response(line, headers, body),
            0
        );
        final int port = server.start();
        target.set(
            new TrimPathSlice(
                new DownloadPackageSlice(
                    URI.create(String.format("http://127.0.0.1:%d/ctx", port)).toURL(),
                    storage
                ),
                "ctx"
            )
        );
        final String url = String.format(
            "http://127.0.0.1:%d/ctx/@hello/simple-npm-project", port
        );
        final WebClient client = WebClient.create(vertx);
        final JsonObject json = client.getAbs(url).rxSend().blockingGet().body().toJsonObject();
        MatcherAssert.assertThat(
            json.getJsonObject("versions").getJsonObject("1.0.1")
                .getJsonObject("dist").getString("tarball"),
            new IsEqual<>(
                String.format(
                    "%s/-/@hello/simple-npm-project-1.0.1.tgz",
                    url
                )
            )
        );
        server.stop();
        vertx.close();
    }
}
