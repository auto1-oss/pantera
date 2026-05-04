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
        return new HttpTuning(Protocol.H2, 1, 100);
    }

    public static HttpTuning fromMap(final Map<String, JsonObject> rows) {
        return new HttpTuning(
            JsonReads.valueOr(rows, "http_client.protocol",
                v -> Protocol.fromString(v.getString("value")), Protocol.H2),
            JsonReads.valueOr(rows, "http_client.http2_max_pool_size",
                v -> v.getInt("value"), 1),
            JsonReads.valueOr(rows, "http_client.http2_multiplexing_limit",
                v -> v.getInt("value"), 100)
        );
    }
}
