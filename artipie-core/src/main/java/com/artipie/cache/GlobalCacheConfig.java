/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import java.util.Optional;

/**
 * Global cache configuration holder.
 * Provides shared Valkey connection for all caches across Artipie.
 * Thread-safe singleton pattern.
 * 
 * @since 1.0
 */
public final class GlobalCacheConfig {
    
    /**
     * Singleton instance.
     */
    private static volatile GlobalCacheConfig instance;
    
    /**
     * Shared Valkey connection.
     */
    private final ValkeyConnection valkey;
    
    /**
     * Private constructor for singleton.
     * @param valkey Valkey connection
     */
    private GlobalCacheConfig(final ValkeyConnection valkey) {
        this.valkey = valkey;
    }
    
    /**
     * Initialize global cache configuration.
     * Should be called once at startup by YamlSettings.
     * 
     * @param valkey Optional Valkey connection
     */
    public static void initialize(final Optional<ValkeyConnection> valkey) {
        if (instance == null) {
            synchronized (GlobalCacheConfig.class) {
                if (instance == null) {
                    instance = new GlobalCacheConfig(valkey.orElse(null));
                }
            }
        }
    }
    
    /**
     * Get the shared Valkey connection.
     * @return Optional Valkey connection
     */
    public static Optional<ValkeyConnection> valkeyConnection() {
        if (instance == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(instance.valkey);
    }
    
    /**
     * Reset for testing purposes.
     */
    static void reset() {
        instance = null;
    }
}
