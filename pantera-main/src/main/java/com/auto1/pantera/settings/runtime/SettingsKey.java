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
 * Each entry documents its default; the {@code SettingsHandler} (Task 6)
 * validates PATCH bodies against this list.
 *
 * <p>{@code defaultRepr} is the JSON literal as it would be stored in the
 * {@code settings.value -> 'value'} field. The protocol default is
 * {@code "\"h2\""} — a quoted JSON string — so
 * {@code Json.createReader(new StringReader(repr)).readValue()} parses it
 * to a JSON string in Task 5's {@code SettingsBootstrap}. Numbers and
 * booleans are JSON literals already ({@code "1"}, {@code "true"}).
 */
public enum SettingsKey {
    HTTP_CLIENT_PROTOCOL("http_client.protocol", "\"h2\""),
    HTTP_CLIENT_H2_MAX_POOL_SIZE("http_client.http2_max_pool_size", "4"),
    HTTP_CLIENT_H2_MULTIPLEXING_LIMIT("http_client.http2_multiplexing_limit", "100"),
    PREFETCH_ENABLED("prefetch.enabled", "true"),
    PREFETCH_CONCURRENCY_GLOBAL("prefetch.concurrency.global", "64"),
    PREFETCH_CONCURRENCY_PER_UPSTREAM("prefetch.concurrency.per_upstream", "16"),
    PREFETCH_CONCURRENCY_PER_UPSTREAM_MAVEN(
        "prefetch.concurrency.per_upstream.maven", "16"),
    PREFETCH_CONCURRENCY_PER_UPSTREAM_GRADLE(
        "prefetch.concurrency.per_upstream.gradle", "16"),
    PREFETCH_CONCURRENCY_PER_UPSTREAM_NPM(
        "prefetch.concurrency.per_upstream.npm", "32"),
    PREFETCH_QUEUE_CAPACITY("prefetch.queue.capacity", "2048"),
    PREFETCH_WORKER_THREADS("prefetch.worker_threads", "8"),
    PREFETCH_CB_DROP_THRESHOLD("prefetch.circuit_breaker.drop_threshold_per_sec", "100"),
    PREFETCH_CB_WINDOW_SECONDS("prefetch.circuit_breaker.window_seconds", "30"),
    PREFETCH_CB_DISABLE_MINUTES("prefetch.circuit_breaker.disable_minutes", "5");

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

    public static boolean isPrefetchKey(final String k) {
        return k.startsWith("prefetch.");
    }
}
