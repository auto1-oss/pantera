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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.slice.LoggingSlice;
import com.auto1.pantera.npm.JsonFromMeta;
import com.auto1.pantera.npm.RandomFreePort;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.vertx.VertxSliceServer;
import io.vertx.reactivex.core.Vertx;
import java.io.DataOutputStream;
import java.util.LinkedList;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * IT for `curl PUT` tgz archive.
 * @since 0.9
 */
@DisabledOnOs(OS.WINDOWS)
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
final class CurlPutIT {

    /**
     * Vert.x used to create tested FileStorage.
     */
    private Vertx vertx;

    /**
     * Storage used as repository.
     */
    private Storage storage;

    /**
     * Server.
     */
    private VertxSliceServer server;

    /**
     * Repository URL.
     */
    private String url;

    @BeforeEach
    void setUp() throws Exception {
        this.vertx = Vertx.vertx();
        this.storage = new InMemoryStorage();
        final int port = new RandomFreePort().value();
        this.url = String.format("http://localhost:%s", port);
        this.server = new VertxSliceServer(
            this.vertx,
            new LoggingSlice(new NpmSlice(
                URI.create(this.url).toURL(), this.storage, (Policy<?>) Policy.FREE,
                new Authentication.Single("testuser", "testpassword"),
                (TokenAuthentication) tkn -> java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty()),
                "*", java.util.Optional.of(new LinkedList<>())
            )),
            port
        );
        this.server.start();
    }

    @AfterEach
    void tearDown() {
        this.server.stop();
        this.vertx.close();
    }

    @ParameterizedTest
    @CsvSource({
        "simple-npm-project-1.0.2.tgz,@hello/simple-npm-project,1.0.2",
        "jQuery-1.7.4.tgz,jQuery,1.7.4"
    })
    void curlPutTgzArchiveWithAndWithoutScopeWorks(
        final String tgz, final String proj, final String vers
    )throws Exception {
        this.putTgz(tgz);
        MatcherAssert.assertThat(
            "Meta file contains uploaded version",
            new JsonFromMeta(this.storage, new Key.From(proj))
                .json().getJsonObject("versions")
                .keySet(),
            Matchers.contains(vers)
        );
        MatcherAssert.assertThat(
            "Tgz archive was uploaded",
            new BlockingStorage(this.storage).exists(
                new Key.From(proj, String.format("-/%s-%s.tgz", proj, vers))
            ),
            new IsEqual<>(true)
        );
    }

    private void putTgz(final String name) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(
                String.format("%s/%s", this.url, name)
            ).toURL().openConnection();
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Basic dGVzdHVzZXI6dGVzdHBhc3N3b3Jk");
            try (DataOutputStream dos = new DataOutputStream(conn.getOutputStream())) {
                dos.write(new TestResource(String.format("binaries/%s", name)).asBytes());
                dos.flush();
            }
            final int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException(
                    String.format("Failed to upload tgz archive: %d", status)
                );
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
