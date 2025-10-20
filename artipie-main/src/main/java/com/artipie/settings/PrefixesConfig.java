/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.settings;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe atomic snapshot of global URL prefixes configuration.
 * Supports hot reload without restart.
 *
 * @since 1.0
 */
public final class PrefixesConfig {

    /**
     * Atomic reference to immutable prefix list.
     */
    private final AtomicReference<List<String>> prefixes;

    /**
     * Configuration version for tracking changes.
     */
    private final AtomicReference<Long> version;

    /**
     * Default constructor with empty prefix list.
     */
    public PrefixesConfig() {
        this(Collections.emptyList());
    }

    /**
     * Constructor with initial prefix list.
     *
     * @param initial Initial list of prefixes
     */
    public PrefixesConfig(final List<String> initial) {
        this.prefixes = new AtomicReference<>(
            Collections.unmodifiableList(initial)
        );
        this.version = new AtomicReference<>(0L);
    }

    /**
     * Get current list of prefixes.
     *
     * @return Immutable list of prefixes
     */
    public List<String> prefixes() {
        return this.prefixes.get();
    }

    /**
     * Get current configuration version.
     *
     * @return Version number
     */
    public long version() {
        return this.version.get();
    }

    /**
     * Update prefixes atomically.
     *
     * @param newPrefixes New list of prefixes
     */
    public void update(final List<String> newPrefixes) {
        this.prefixes.set(Collections.unmodifiableList(newPrefixes));
        this.version.updateAndGet(v -> v + 1);
    }

    /**
     * Check if a given string is a configured prefix.
     *
     * @param candidate String to check
     * @return True if candidate is a configured prefix
     */
    public boolean isPrefix(final String candidate) {
        return this.prefixes.get().contains(candidate);
    }
}
