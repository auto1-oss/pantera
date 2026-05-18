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

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Canonical catalog of all settings keys used by the runtime cache.
 * Each entry documents its default; the {@code SettingsHandler} validates
 * PATCH bodies against this list.
 *
 * <p>{@code defaultRepr} is the JSON literal as it would be stored in the
 * {@code settings.value -> 'value'} field — numbers and booleans are JSON
 * literals already ({@code "1"}, {@code "true"}); strings would be quoted
 * JSON ({@code "\"value\""}). {@code Json.createReader(new StringReader(repr))
 * .readValue()} round-trips every default.
 */
public enum SettingsKey {
    BULKHEAD_ADAPTIVE("http_client.bulkhead.adaptive", "true"),
    BULKHEAD_MIN_PERMITS("http_client.bulkhead.min_permits", "5"),
    BULKHEAD_MAX_PERMITS("http_client.bulkhead.max_permits", "100"),
    BULKHEAD_INITIAL_PERMITS("http_client.bulkhead.initial_permits", "10"),
    BULKHEAD_TARGET_P99_MS("http_client.bulkhead.target_p99_ms", "500"),
    BULKHEAD_WINDOW_SECONDS("http_client.bulkhead.window_seconds", "5"),
    BULKHEAD_RAMP_UP_STEP("http_client.bulkhead.ramp_up_step", "1"),
    BULKHEAD_RAMP_DOWN_FACTOR("http_client.bulkhead.ramp_down_factor", "0.5");

    private static final Set<String> ALL_KEYS = Arrays.stream(values())
        .map(SettingsKey::key)
        .collect(Collectors.toUnmodifiableSet());

    private final String key;
    private final String defaultRepr;

    SettingsKey(final String key, final String defaultRepr) {
        this.key = key;
        this.defaultRepr = defaultRepr;
    }

    public String key() {
        return this.key;
    }

    public String defaultRepr() {
        return this.defaultRepr;
    }

    public static Set<String> allKeys() {
        return ALL_KEYS;
    }

    public static boolean isHttpKey(final String k) {
        return k.startsWith("http_client.");
    }
}
