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
package com.auto1.pantera.scheduling;

import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.misc.ConfigDefaults;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static registry for non-serializable Quartz job data.
 * <p>
 * When Quartz uses JDBC job store (clustering mode), all {@code JobDataMap}
 * entries must be serializable because they are persisted in the database.
 * However, runtime objects like {@link java.util.Queue} and
 * {@link java.util.function.Consumer} cannot be serialized.
 * <p>
 * This registry allows jobs to store non-serializable data by key in JVM
 * memory and place only the key (a {@code String}) in the {@code JobDataMap}.
 * The job retrieves the actual object from the registry at execution time.
 * <p>
 * In a clustered setup, each node maintains its own registry. Since Quartz
 * ensures a given trigger fires on only one node at a time, the node that
 * scheduled the job always has the data in its registry.
 * <p>
 * A defensive sanity cap ({@value #DEFAULT_MAX_ENTRIES} entries by default,
 * overridable via {@code PANTERA_JOB_DATA_REGISTRY_MAX}) is enforced. The
 * cap is NOT a hard limit — {@link #register} still accepts the entry so
 * jobs never silently drop — but an overflow crosses a loud error log
 * which flags a scheduler-side ref leak for operators.
 *
 * @since 1.20.13
 */
public final class JobDataRegistry {

    /**
     * Default sanity cap for registered entries.
     */
    private static final int DEFAULT_MAX_ENTRIES = 10_000;

    /**
     * Sanity cap resolved from env / sysprop / default at class-load time.
     */
    private static final int MAX_ENTRIES = ConfigDefaults.getInt(
        "PANTERA_JOB_DATA_REGISTRY_MAX", DEFAULT_MAX_ENTRIES
    );

    /**
     * In-memory store for non-serializable job data.
     */
    private static final Map<String, Object> DATA = new ConcurrentHashMap<>();

    /**
     * Private ctor.
     */
    private JobDataRegistry() {
        // Utility class
    }

    /**
     * Register a non-serializable value by key.
     * @param key Unique key for the data
     * @param value Runtime object (Queue, Consumer, etc.)
     */
    public static void register(final String key, final Object value) {
        final int size = DATA.size();
        if (size >= MAX_ENTRIES) {
            EcsLogger.error("com.auto1.pantera.scheduling")
                .message("JobDataRegistry overflow — scheduler bug leaking refs"
                    + " (entry_count=" + size
                    + ", key_prefix=" + key.substring(0, Math.min(32, key.length())) + ")")
                .eventCategory("process")
                .eventAction("job_data_overflow")
                .eventOutcome("failure")
                .log();
        }
        DATA.put(key, value);
    }

    /**
     * Look up a previously registered value.
     * @param key Registry key
     * @param <T> Expected type
     * @return The registered object, or null if not found
     */
    @SuppressWarnings("unchecked")
    public static <T> T lookup(final String key) {
        return (T) DATA.get(key);
    }

    /**
     * Remove a registered value.
     * @param key Registry key to remove
     */
    public static void remove(final String key) {
        DATA.remove(key);
    }
}
