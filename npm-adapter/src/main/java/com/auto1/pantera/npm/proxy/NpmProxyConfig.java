/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.proxy;

import java.time.Duration;

/**
 * NPM proxy configuration for uplink registries.
 * 
 * <p>Allows per-uplink configuration of timeouts, retries, and caching policies.</p>
 * 
 * <p>Example YAML configuration:
 * <pre>
 * remotes:
 *   - url: https://registry.npmjs.org/
 *     timeout: 30s
 *     maxRetries: 3
 *     cacheMaxAge: 2m
 *     failTimeout: 5m
 *     connectionPool:
 *       maxConnections: 50
 *       keepAlive: true
 *       idleTimeout: 30s
 * </pre>
 * </p>
 *
 * @since 1.19
 */
public final class NpmProxyConfig {
    
    /**
     * Default timeout duration.
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    
    /**
     * Default max retries.
     */
    private static final int DEFAULT_MAX_RETRIES = 3;
    
    /**
     * Default cache max age.
     */
    private static final Duration DEFAULT_CACHE_MAX_AGE = Duration.ofMinutes(2);
    
    /**
     * Default fail timeout (cooldown after failures).
     */
    private static final Duration DEFAULT_FAIL_TIMEOUT = Duration.ofMinutes(5);
    
    /**
     * Default max connections.
     */
    private static final int DEFAULT_MAX_CONNECTIONS = 50;
    
    /**
     * Request timeout duration.
     */
    private final Duration timeout;
    
    /**
     * Maximum number of retry attempts.
     */
    private final int maxRetries;
    
    /**
     * Cache max age duration.
     */
    private final Duration cacheMaxAge;
    
    /**
     * Fail timeout (cooldown period after failures).
     */
    private final Duration failTimeout;
    
    /**
     * Maximum connections in pool.
     */
    private final int maxConnections;
    
    /**
     * Whether to keep connections alive.
     */
    private final boolean keepAlive;
    
    /**
     * Idle timeout for connections.
     */
    private final Duration idleTimeout;
    
    /**
     * Ctor with all parameters.
     * 
     * @param timeout Request timeout
     * @param maxRetries Maximum retry attempts
     * @param cacheMaxAge Cache max age
     * @param failTimeout Fail timeout (cooldown)
     * @param maxConnections Maximum connections in pool
     * @param keepAlive Whether to keep connections alive
     * @param idleTimeout Idle timeout for connections
     */
    public NpmProxyConfig(
        final Duration timeout,
        final int maxRetries,
        final Duration cacheMaxAge,
        final Duration failTimeout,
        final int maxConnections,
        final boolean keepAlive,
        final Duration idleTimeout
    ) {
        this.timeout = timeout;
        this.maxRetries = maxRetries;
        this.cacheMaxAge = cacheMaxAge;
        this.failTimeout = failTimeout;
        this.maxConnections = maxConnections;
        this.keepAlive = keepAlive;
        this.idleTimeout = idleTimeout;
    }
    
    /**
     * Default configuration.
     * 
     * @return Default NPM proxy configuration
     */
    public static NpmProxyConfig defaultConfig() {
        return new NpmProxyConfig(
            DEFAULT_TIMEOUT,
            DEFAULT_MAX_RETRIES,
            DEFAULT_CACHE_MAX_AGE,
            DEFAULT_FAIL_TIMEOUT,
            DEFAULT_MAX_CONNECTIONS,
            true, // keepAlive
            Duration.ofSeconds(30) // idleTimeout
        );
    }
    
    /**
     * Get request timeout.
     * 
     * @return Timeout duration
     */
    public Duration timeout() {
        return this.timeout;
    }
    
    /**
     * Get max retry attempts.
     * 
     * @return Max retries
     */
    public int maxRetries() {
        return this.maxRetries;
    }
    
    /**
     * Get cache max age.
     * 
     * @return Cache max age duration
     */
    public Duration cacheMaxAge() {
        return this.cacheMaxAge;
    }
    
    /**
     * Get fail timeout (cooldown period).
     * 
     * @return Fail timeout duration
     */
    public Duration failTimeout() {
        return this.failTimeout;
    }
    
    /**
     * Get maximum connections in pool.
     * 
     * @return Max connections
     */
    public int maxConnections() {
        return this.maxConnections;
    }
    
    /**
     * Check if keep-alive is enabled.
     * 
     * @return True if keep-alive enabled
     */
    public boolean keepAlive() {
        return this.keepAlive;
    }
    
    /**
     * Get idle timeout for connections.
     * 
     * @return Idle timeout duration
     */
    public Duration idleTimeout() {
        return this.idleTimeout;
    }
    
    /**
     * Builder for NPM proxy configuration.
     */
    public static final class Builder {
        /**
         * Timeout.
         */
        private Duration timeout = DEFAULT_TIMEOUT;
        
        /**
         * Max retries.
         */
        private int maxRetries = DEFAULT_MAX_RETRIES;
        
        /**
         * Cache max age.
         */
        private Duration cacheMaxAge = DEFAULT_CACHE_MAX_AGE;
        
        /**
         * Fail timeout.
         */
        private Duration failTimeout = DEFAULT_FAIL_TIMEOUT;
        
        /**
         * Max connections.
         */
        private int maxConnections = DEFAULT_MAX_CONNECTIONS;
        
        /**
         * Keep alive flag.
         */
        private boolean keepAlive = true;
        
        /**
         * Idle timeout.
         */
        private Duration idleTimeout = Duration.ofSeconds(30);
        
        /**
         * Set timeout.
         * 
         * @param timeout Timeout duration
         * @return This builder
         */
        public Builder timeout(final Duration timeout) {
            this.timeout = timeout;
            return this;
        }
        
        /**
         * Set max retries.
         * 
         * @param maxRetries Max retry attempts
         * @return This builder
         */
        public Builder maxRetries(final int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }
        
        /**
         * Set cache max age.
         * 
         * @param cacheMaxAge Cache max age duration
         * @return This builder
         */
        public Builder cacheMaxAge(final Duration cacheMaxAge) {
            this.cacheMaxAge = cacheMaxAge;
            return this;
        }
        
        /**
         * Set fail timeout.
         * 
         * @param failTimeout Fail timeout duration
         * @return This builder
         */
        public Builder failTimeout(final Duration failTimeout) {
            this.failTimeout = failTimeout;
            return this;
        }
        
        /**
         * Set max connections.
         * 
         * @param maxConnections Max connections in pool
         * @return This builder
         */
        public Builder maxConnections(final int maxConnections) {
            this.maxConnections = maxConnections;
            return this;
        }
        
        /**
         * Set keep alive flag.
         * 
         * @param keepAlive Keep alive enabled
         * @return This builder
         */
        public Builder keepAlive(final boolean keepAlive) {
            this.keepAlive = keepAlive;
            return this;
        }
        
        /**
         * Set idle timeout.
         * 
         * @param idleTimeout Idle timeout duration
         * @return This builder
         */
        public Builder idleTimeout(final Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
            return this;
        }
        
        /**
         * Build configuration.
         * 
         * @return NPM proxy configuration
         */
        public NpmProxyConfig build() {
            return new NpmProxyConfig(
                this.timeout,
                this.maxRetries,
                this.cacheMaxAge,
                this.failTimeout,
                this.maxConnections,
                this.keepAlive,
                this.idleTimeout
            );
        }
    }
}
