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

import java.util.Map;
import javax.json.Json;
import javax.json.JsonObject;
import com.auto1.pantera.settings.runtime.HttpTuning.Protocol;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for v2.2.0 typed settings snapshot records and the {@link SettingsKey}
 * catalog. These are pure value-object tests; no DB or Vertx required.
 */
final class SettingsKeyTest {

    @Test
    void httpTuningHasSpecDefaults() {
        final HttpTuning t = HttpTuning.defaults();
        assertEquals(Protocol.H2, t.protocol(), "default protocol must be H2");
        assertEquals(1, t.h2MaxPoolSize(), "default h2 pool size must be 1");
        assertEquals(100, t.h2MultiplexingLimit(),
            "default h2 multiplexing limit must be 100");
    }

    @Test
    void prefetchTuningHasSpecDefaults() {
        final PrefetchTuning p = PrefetchTuning.defaults();
        assertTrue(p.enabled(), "prefetch must default to enabled");
        assertEquals(64, p.globalConcurrency(), "default global concurrency must be 64");
        assertEquals(16, p.perUpstreamConcurrency(),
            "default per-upstream concurrency must be 16");
        assertEquals(2048, p.queueCapacity(), "default queue capacity must be 2048");
        assertEquals(8, p.workerThreads(), "default worker threads must be 8");
    }

    @Test
    void httpTuningParsesProtocolFromJson() {
        final JsonObject protoRow = Json.createObjectBuilder().add("value", "h1").build();
        final JsonObject poolRow = Json.createObjectBuilder().add("value", 4).build();
        final Map<String, JsonObject> rows = Map.of(
            "http_client.protocol", protoRow,
            "http_client.http2_max_pool_size", poolRow
        );
        final HttpTuning t = HttpTuning.fromMap(rows);
        assertEquals(Protocol.H1, t.protocol(), "protocol must parse from JSON value");
        assertEquals(4, t.h2MaxPoolSize(), "pool size must parse from JSON value");
        assertEquals(100, t.h2MultiplexingLimit(),
            "multiplexing limit absent from input → keep default 100");
    }
}
