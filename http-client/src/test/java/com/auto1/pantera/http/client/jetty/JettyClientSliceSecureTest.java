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
package com.auto1.pantera.http.client.jetty;

import com.auto1.pantera.asto.test.TestResource;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.JksOptions;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * Tests for {@link JettyClientSlice} with HTTPS server.
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
public final class JettyClientSliceSecureTest extends JettyClientSliceTest {

    @Override
    HttpClient newHttpClient() {
        final SslContextFactory.Client factory = new SslContextFactory.Client();
        factory.setTrustAll(true);
        final HttpClient client = new HttpClient();
        client.setSslContextFactory(factory);
        return client;
    }

    @Override
    HttpServerOptions newHttpServerOptions() {
        return super.newHttpServerOptions()
            .setSsl(true)
            .setKeyStoreOptions(
                new JksOptions()
                    .setPath(
                        new TestResource("keystore").asPath().toString()
                    )
                    .setPassword("123456")
            );
    }
}
