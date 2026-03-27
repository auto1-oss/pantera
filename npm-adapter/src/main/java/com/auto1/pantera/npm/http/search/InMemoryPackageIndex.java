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
package com.auto1.pantera.npm.http.search;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.cache.CacheConfig;
import com.auto1.pantera.cache.ValkeyConnection;
import com.auto1.pantera.http.misc.ConfigDefaults;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * In-memory package index implementation using Caffeine cache.
 * Provides bounded, configurable caching for npm package search.
 * 
 * <p>Configuration in _server.yaml:
 * <pre>
 * caches:
 *   npm-search:
 *     profile: large  # Or direct: maxSize: 50000, ttl: 24h
 * </pre>
 *
 * @since 1.1
 */
public final class InMemoryPackageIndex implements PackageIndex {
    
    /**
     * L1 packages cache (name -> metadata).
     */
    private final Cache<String, PackageMetadata> packages;
    
    /**
     * L2 cache (Valkey/Redis, warm data) - optional.
     */
    private final RedisAsyncCommands<String, byte[]> l2;
    
    /**
     * Whether two-tier caching is enabled.
     */
    private final boolean twoTier;
    
    /**
     * Cache TTL for L2.
     */
    private final Duration ttl;
    
    /**
     * Constructor with default configuration.
     * Auto-connects to Valkey if GlobalCacheConfig is initialized.
     */
    public InMemoryPackageIndex() {
        this(com.auto1.pantera.cache.GlobalCacheConfig.valkeyConnection().orElse(null));
    }
    
    /**
     * Constructor with Valkey connection (two-tier).
     * @param valkey Valkey connection for L2 cache
     */
    public InMemoryPackageIndex(final ValkeyConnection valkey) {
        this.twoTier = (valkey != null);
        this.l2 = this.twoTier ? valkey.async() : null;
        this.ttl = Duration.ofHours(
            ConfigDefaults.getLong("PANTERA_NPM_INDEX_TTL_HOURS", 24L)
        );
        
        // L1: Hot data cache
        final Duration l1Ttl = this.twoTier ? Duration.ofMinutes(5) : this.ttl;
        final int l1Size = this.twoTier ? 5_000 : 50_000;
        
        this.packages = Caffeine.newBuilder()
            .maximumSize(l1Size)
            .expireAfterWrite(l1Ttl)
            .recordStats()
            .build();
    }
    
    /**
     * Constructor with configuration support.
     * @param serverYaml Server configuration YAML
     */
    public InMemoryPackageIndex(final YamlMapping serverYaml) {
        this(serverYaml, null);
    }
    
    /**
     * Constructor with configuration and Valkey support.
     * @param serverYaml Server configuration YAML
     * @param valkey Valkey connection for L2 cache (null uses GlobalCacheConfig)
     */
    public InMemoryPackageIndex(final YamlMapping serverYaml, final ValkeyConnection valkey) {
        // Check global config if no explicit valkey passed
        final ValkeyConnection actualValkey = (valkey != null) 
            ? valkey 
            : com.auto1.pantera.cache.GlobalCacheConfig.valkeyConnection().orElse(null);
        
        final CacheConfig config = CacheConfig.from(serverYaml, "npm-search");
        this.twoTier = (actualValkey != null && config.valkeyEnabled());
        this.l2 = this.twoTier ? actualValkey.async() : null;
        // Use l2Ttl for L2 storage, main ttl for single-tier
        this.ttl = this.twoTier ? config.l2Ttl() : config.ttl();
        
        // L1: Hot data cache - use configured TTLs
        final Duration l1Ttl = this.twoTier ? config.l1Ttl() : config.ttl();
        final int l1Size = this.twoTier ? config.l1MaxSize() : config.maxSize();
        
        this.packages = Caffeine.newBuilder()
            .maximumSize(l1Size)
            .expireAfterWrite(l1Ttl)
            .recordStats()
            .build();
    }
    
    @Override
    public CompletableFuture<List<PackageMetadata>> search(
        final String query,
        final int size,
        final int from
    ) {
        final String lowerQuery = query.toLowerCase(Locale.ROOT);
        
        final List<PackageMetadata> results = this.packages.asMap().values().stream()
            .filter(pkg -> this.matches(pkg, lowerQuery))
            .skip(from)
            .limit(size)
            .collect(Collectors.toList());
            
        return CompletableFuture.completedFuture(results);
    }
    
    @Override
    public CompletableFuture<Void> index(final PackageMetadata metadata) {
        // Cache in L1
        this.packages.put(metadata.name(), metadata);
        
        // Cache in L2 (if enabled)
        if (this.twoTier) {
            final String redisKey = "npm:search:" + metadata.name();
            // Simple serialization: just the name (full metadata retrieval from storage)
            final byte[] value = metadata.name().getBytes(StandardCharsets.UTF_8);
            this.l2.setex(redisKey, this.ttl.getSeconds(), value);
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> remove(final String packageName) {
        // Remove from L1
        this.packages.invalidate(packageName);
        
        // Remove from L2 (if enabled)
        if (this.twoTier) {
            final String redisKey = "npm:search:" + packageName;
            this.l2.del(redisKey);
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Check if package matches query.
     * @param pkg Package metadata
     * @param query Query (lowercase)
     * @return True if matches
     */
    private boolean matches(final PackageMetadata pkg, final String query) {
        final String lowerName = pkg.name().toLowerCase(Locale.ROOT);
        final String lowerDesc = pkg.description().toLowerCase(Locale.ROOT);
        
        if (lowerName.contains(query) || lowerDesc.contains(query)) {
            return true;
        }
        
        return pkg.keywords().stream()
            .anyMatch(kw -> kw.toLowerCase(Locale.ROOT).contains(query));
    }
}
