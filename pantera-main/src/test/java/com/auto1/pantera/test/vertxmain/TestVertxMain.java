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
package com.auto1.pantera.test.vertxmain;

import com.auto1.pantera.VertxMain;

public class TestVertxMain implements AutoCloseable {

    private final int port;
    private final VertxMain server;

    public TestVertxMain(int port, VertxMain server) {
        this.port = port;
        this.server = server;
    }

    public int port() {
        return port;
    }

    @Override
    public void close() {
        server.stop();
    }
}
