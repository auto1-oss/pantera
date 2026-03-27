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
package com.auto1.pantera.api.ssl;

import com.auto1.pantera.asto.Storage;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerOptions;

/**
 * Key store.
 * @since 0.26
 */
public interface KeyStore {
    /**
     * Checks if SSL is enabled.
     * @return True is SSL enabled.
     */
    boolean enabled();

    /**
     * Checks if configuration for this type of KeyStore is present.
     * @return True if it is configured.
     */
    boolean isConfigured();

    /**
     * Provides SSL-options for http server.
     * @param vertx Vertx.
     * @param storage Pantera settings storage.
     * @return HttpServer
     */
    HttpServerOptions secureOptions(Vertx vertx, Storage storage);
}
