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
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.http.slice.LoggingSlice;
import com.auto1.pantera.npm.RandomFreePort;
import com.auto1.pantera.vertx.VertxSliceServer;
import com.jcabi.log.Logger;
import io.vertx.reactivex.core.Vertx;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedList;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.text.StringContainsInOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;

/**
 * IT for installation after publishing through `curl PUT` tgz archive.
 * @since 0.9
 */
@DisabledOnOs(OS.WINDOWS)
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
final class InstallCurlPutIT {
    /**
     * Temporary directory for all tests.
     */
    @TempDir
    Path tmp;

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

    /**
     * Container.
     */
    private GenericContainer<?> cntn;

    /**
     * Server port.
     */
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        this.vertx = Vertx.vertx();
        this.storage = new FileStorage(this.tmp);
        this.port = new RandomFreePort().value();
        this.url = String.format("http://host.testcontainers.internal:%s", this.port);
        this.server = new VertxSliceServer(
            this.vertx,
            new LoggingSlice(new NpmSlice(URI.create(this.url).toURL(), this.storage, new LinkedList<>())),
            this.port
        );
        this.server.start();
        Testcontainers.exposeHostPorts(this.port);
        this.cntn = new GenericContainer<>("node:14-alpine")
            .withCommand("tail", "-f", "/dev/null")
            .withWorkingDirectory("/home/")
            .withFileSystemBind(this.tmp.toString(), "/home");
        this.cntn.start();
    }

    @AfterEach
    void tearDown() {
        this.server.stop();
        this.vertx.close();
        this.cntn.stop();
    }

    @ParameterizedTest
    @CsvSource({
        "simple-npm-project-1.0.2.tgz,@hello/simple-npm-project,1.0.2",
        "jQuery-1.7.4.tgz,jQuery,1.7.4"
    })
    void installationCurlPutTgzArchiveWithAndWithoutScopeWorks(
        final String tgz, final String proj, final String vers
    ) throws Exception {
        this.putTgz(tgz);
        MatcherAssert.assertThat(
            "Tgz archive was uploaded",
            new BlockingStorage(this.storage).exists(
                new Key.From(proj, String.format("-/%s-%s.tgz", proj, vers))
            ),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Package was successfully installed",
            this.exec("npm", "install", proj, "--registry", this.url),
            new StringContainsInOrder(
                Arrays.asList(
                    String.format("+ %s@%s", proj, vers),
                    "added 1 package"
                )
            )
        );
    }

    private void putTgz(final String name) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(
                String.format("http://localhost:%d/%s", this.port, name)
            ).toURL().openConnection();
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
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

    private String exec(final String... command) throws Exception {
        final Container.ExecResult res = this.cntn.execInContainer(command);
        Logger.debug(this, "Command:\n%s\nResult:\n%s", String.join(" ", command), res.toString());
        return res.getStdout();
    }
}
