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
package com.auto1.pantera.npm.misc;

import com.auto1.pantera.http.log.EcsLogger;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;

/**
 * NextSafeAvailablePort.
 *
 * @since 0.1
 */
public class NextSafeAvailablePort {

    /**
     * The minimum number of server port number as first non-privileged port.
     */
    private static final int MIN_PORT = 1024;

    /**
     * The maximum number of server port number.
     */
    private static final int MAX_PORT = 49_151;

    /**
     * The first and minimum port to scan for availability.
     */
    private final int from;

    /**
     * Ctor.
     */
    public NextSafeAvailablePort() {
        this(NextSafeAvailablePort.MIN_PORT);
    }

    /**
     * Ctor.
     *
     * @param from Port to start scan from
     */
    public NextSafeAvailablePort(final int from) {
        this.from = from;
    }

    /**
     * Gets the next available port starting at a port.
     *
     * @return Next available port
     * @throws IllegalArgumentException if there are no ports available
     */
    public int value() {
        if (this.from < NextSafeAvailablePort.MIN_PORT
            || this.from > NextSafeAvailablePort.MAX_PORT) {
            throw new IllegalArgumentException(
                String.format(
                    "Invalid start port: %d", this.from
                )
            );
        }
        for (int port = this.from; port <= NextSafeAvailablePort.MAX_PORT; port += 1) {
            if (available(port)) {
                return port;
            }
        }
        throw new IllegalArgumentException(
            String.format(
                "Could not find an available port above %d", this.from
            )
        );
    }

    /**
     * Checks to see if a specific port is available.
     *
     * @param port The port to check for availability
     * @return If the ports is available
     */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
    private static boolean available(final int port) {
        try (ServerSocket sersock = new ServerSocket(port);
            DatagramSocket dgrmsock = new DatagramSocket(port)
        ) {
            sersock.setReuseAddress(true);
            dgrmsock.setReuseAddress(true);
            return true;
        } catch (final IOException ex) {
            EcsLogger.debug("com.auto1.pantera.npm")
                .message("Port not available")
                .error(ex)
                .log();
        }
        return false;
    }
}
