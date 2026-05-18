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
package com.auto1.pantera.settings.runtime;

import io.vertx.core.http.HttpVersion;
import java.util.Locale;
import java.util.Map;
import javax.json.JsonObject;

/**
 * Immutable typed snapshot of HTTP-client tunables sourced from the
 * {@code settings} table. Constructed via {@link #defaults()} or
 * {@link #fromMap(Map)}; never mutated. The {@code RuntimeSettingsCache}
 * (Task 4) hands the resulting record to the request path.
 */
public record HttpTuning(
    Protocol protocol,
    int h2MaxPoolSize,
    int h2MultiplexingLimit
) {
    public enum Protocol {
        H2, H1, AUTO;

        public HttpVersion vertxVersion() {
            return switch (this) {
                case H2, AUTO -> HttpVersion.HTTP_2;
                case H1 -> HttpVersion.HTTP_1_1;
            };
        }

        public static Protocol fromString(final String s) {
            if (s == null) {
                throw new IllegalArgumentException("http_client.protocol value is null");
            }
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "h2" -> H2;
                case "h1" -> H1;
                case "auto" -> AUTO;
                default -> throw new IllegalArgumentException(
                    "unknown http_client.protocol value: " + s + " (expected one of h2, h1, auto)");
            };
        }
    }

    public static HttpTuning defaults() {
        // Protocol = AUTO: ALPN negotiates h2 with capable peers and
        // gracefully falls back to h1.1 for the rest. The earlier H2-only
        // default was fragile against upstreams that mid-stream RST_STREAM
        // (proxy.golang.org, some Cloudflare-fronted registries) and
        // against legacy proxies that don't speak h2c. AUTO + the
        // backpressured response-body bridge (JettyContentSourcePublisher)
        // is the safest production-stable default.
        //
        // h2MaxPoolSize=4 (Phase 7 perf bench, 2026-05): enables true
        // upstream parallelism over multiplexed h2 streams without
        // overwhelming origins. h2MultiplexingLimit=100 matches what
        // most modern CDNs advertise via SETTINGS.
        return new HttpTuning(Protocol.AUTO, 4, 100);
    }

    public static HttpTuning fromMap(final Map<String, JsonObject> rows) {
        return new HttpTuning(
            JsonReads.valueOr(rows, "http_client.protocol",
                v -> Protocol.fromString(v.getString("value")), Protocol.AUTO),
            JsonReads.valueOr(rows, "http_client.http2_max_pool_size",
                v -> v.getInt("value"), 4),
            JsonReads.valueOr(rows, "http_client.http2_multiplexing_limit",
                v -> v.getInt("value"), 100)
        );
    }
}
