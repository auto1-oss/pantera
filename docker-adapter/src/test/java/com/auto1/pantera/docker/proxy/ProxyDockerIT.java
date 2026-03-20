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
package com.auto1.pantera.docker.proxy;

import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.http.client.HttpClientSettings;
import com.auto1.pantera.http.client.jetty.JettyClientSlices;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsAnything;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import wtf.g4s8.hamcrest.json.JsonHas;
import wtf.g4s8.hamcrest.json.StringIsJson;

/**
 * Integration tests for {@link ProxyDocker}.
 */
final class ProxyDockerIT {

    /**
     * HTTP client used for proxy.
     */
    private JettyClientSlices client;

    /**
     * Proxy docker.
     */
    private ProxyDocker docker;

    @BeforeEach
    void setUp() {
        this.client = new JettyClientSlices(
            new HttpClientSettings().setFollowRedirects(true)
        );
        this.client.start();
        this.docker = new ProxyDocker("test_registry", this.client.https("mcr.microsoft.com"));
    }

    @AfterEach
    void tearDown() {
        this.client.stop();
    }

    @Test
    void readsCatalog() {
        MatcherAssert.assertThat(
            this.docker.catalog(Pagination.empty())
                .thenApply(Catalog::json)
                .toCompletableFuture().join().asString(),
            new StringIsJson.Object(new JsonHas("repositories", new IsAnything<>()))
        );
    }
}
