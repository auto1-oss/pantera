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
package com.auto1.pantera.nuget;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Provides random free port to use in tests.
 * @since 0.12
 */
public final class RandomFreePort {
    /**
     * Random free port.
     */
    private final int port;

    /**
     * Ctor.
     * @throws IOException if fails to open port
     */
    public RandomFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            this.port = socket.getLocalPort();
        }
    }

    /**
     * Returns free port.
     * @return Free port
     */
    public int value() {
        return this.port;
    }
}