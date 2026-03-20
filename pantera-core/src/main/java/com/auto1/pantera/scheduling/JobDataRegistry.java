/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.scheduling;

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
 *
 * @since 1.20.13
 */
public final class JobDataRegistry {

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
