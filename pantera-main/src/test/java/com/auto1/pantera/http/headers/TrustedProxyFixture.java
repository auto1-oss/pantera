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
package com.auto1.pantera.http.headers;

import java.util.Map;

/**
 * Test fixture that declares a trusted reverse proxy
 * ({@code trust_forwarded_headers=true}) through the loader's env tier,
 * for tests outside this package. Surefire runs each test class in its
 * own JVM here, but callers still {@link #close()} in {@code @AfterEach}.
 *
 * @since 2.2.9
 */
public final class TrustedProxyFixture implements AutoCloseable {

    /**
     * Install the trusted-proxy settings.
     */
    public TrustedProxyFixture() {
        ClientBaseUrlSettingsLoader.install(
            null, Map.of("PANTERA_TRUST_FORWARDED_HEADERS", "true")::get
        );
    }

    @Override
    public void close() {
        ClientBaseUrlSettingsLoader.uninstall();
    }
}
