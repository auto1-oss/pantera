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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.client.auth.BasicAuthenticator;
import com.auto1.pantera.http.client.jetty.JettyClientSlices;
import com.auto1.pantera.http.slice.LoggingSlice;
import com.auto1.pantera.pypi.PypiDeployment;
import com.auto1.pantera.security.policy.PolicyByUsername;
import com.auto1.pantera.vertx.VertxSliceServer;
import io.vertx.reactivex.core.Vertx;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Test for {@link PyProxySlice} with authorisation.
 * @since 0.7
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class PyProxySliceAuthITCase {

    /**
     * Vertx instance.
     */
    private static final Vertx VERTX = Vertx.vertx();

    /**
     * Jetty client.
     */
    private final JettyClientSlices client = new JettyClientSlices();

    /**
     * Vertx slice origin server instance.
     */
    private VertxSliceServer origin;

    /**
     * Vertx slice proxy server instance.
     */
    private VertxSliceServer proxy;

    /**
     * Pypi container.
     */
    @RegisterExtension
    private final PypiDeployment container = new PypiDeployment();

    @BeforeEach
    void setUp() throws Exception {
        final String bob = "Bob";
        final String pswd = "abc123";
        final Storage storage = new InMemoryStorage();
        new TestResource("pypi_repo/alarmtime-0.1.5.tar.gz")
            .saveTo(storage, new Key.From("alarmtime", "alarmtime-0.1.5.tar.gz"));
        this.origin = new VertxSliceServer(
            PyProxySliceAuthITCase.VERTX,
            new LoggingSlice(
                new PySlice(
                    storage, new PolicyByUsername(bob),
                    new Authentication.Single(bob, pswd),
                    "test",
                    Optional.empty()
                )
            )
        );
        this.client.start();
        this.proxy = new VertxSliceServer(
            PyProxySliceAuthITCase.VERTX,
            new LoggingSlice(
                new PyProxySlice(
                    this.client,
                    URI.create(String.format("http://localhost:%d", this.origin.start())),
                    new BasicAuthenticator(bob, pswd),
                    new InMemoryStorage(),
                    Optional.empty(),
                    "my-proxy",
                    "pypi-proxy",
                    com.auto1.pantera.cooldown.impl.NoopCooldownService.INSTANCE
                )
            ),
            this.container.port()
        );
        this.proxy.start();
    }

    @Test
    void installsFromProxy() throws IOException, InterruptedException {
        MatcherAssert.assertThat(
            this.container.bash(
                String.format(
                    "pip install --index-url %s --no-deps --trusted-host host.testcontainers.internal \"alarmtime\"",
                    this.container.localAddress()
                )
            ),
            Matchers.containsString("Successfully installed alarmtime-0.1.5")
        );
    }

    @AfterEach
    void close() throws Exception {
        this.proxy.stop();
        this.origin.stop();
        this.client.stop();
    }

}
