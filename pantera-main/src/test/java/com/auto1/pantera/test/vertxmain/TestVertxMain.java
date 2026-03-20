/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
