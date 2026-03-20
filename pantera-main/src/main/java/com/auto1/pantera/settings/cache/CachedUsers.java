/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.settings.cache;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.asto.misc.Cleanable;
import com.auto1.pantera.asto.misc.UncheckedIOScalar;
import com.auto1.pantera.cache.CacheConfig;
import com.auto1.pantera.cache.ValkeyConnection;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.misc.ArtipieProperties;
import com.auto1.pantera.misc.Property;
import com.auto1.pantera.settings.JwtSettings;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;

/**
 * Cached authentication decorator.
 * <p>
 * It remembers the result of decorated authentication provider and returns it
 * instead of calling origin authentication.
 * </p>
 * 
 * <p>Configuration in _server.yaml:
 * <pre>
 * caches:
 *   auth:
 *     profile: small  # Or direct: maxSize: 10000, ttl: 5m
 * </pre>
 * 
 * @since 0.22
 */
public final class CachedUsers implements Authentication, Cleanable<String> {
    /**
     * L1 cache for credentials (in-memory, hot data).
     */
    private final Cache<String, Optional<AuthUser>> cached;
    
    /**
     * L2 cache (Valkey/Redis, warm data) - optional.
     */
    private final RedisAsyncCommands<String, byte[]> l2;
    
    /**
     * Whether two-tier caching is enabled.
     */
    private final boolean twoTier;
    
    /**
     * TTL for L2 cache.
     */
    private final Duration ttl;

    /**
     * Origin authentication.
     */
    private final Authentication origin;

    /**
     * Ctor with default configuration.
     * Here an instance of cache is created. It is important that cache
     * is a local variable.
     * @param origin Origin authentication
     */
    public CachedUsers(final Authentication origin) {
        this(origin, (ValkeyConnection) null, null);
    }
    
    /**
     * Ctor with Valkey connection (two-tier).
     * @param origin Origin authentication
     * @param valkey Valkey connection for L2 cache
     */
    public CachedUsers(final Authentication origin, final ValkeyConnection valkey) {
        this(origin, valkey, null);
    }

    /**
     * Ctor with Valkey connection and JWT settings.
     * Note: JWT-as-password bypasses the cache entirely (validated via exp claim),
     * so this cache only applies to direct Basic Auth with IdP passwords.
     * @param origin Origin authentication
     * @param valkey Valkey connection for L2 cache (optional)
     * @param jwtSettings JWT settings (kept for backward compat, not used for cache TTL)
     */
    public CachedUsers(
        final Authentication origin,
        final ValkeyConnection valkey,
        final JwtSettings jwtSettings
    ) {
        this.origin = origin;
        this.twoTier = (valkey != null);
        this.l2 = this.twoTier ? valkey.async() : null;
        
        // TTL from property - applies only to direct Basic Auth (IdP passwords)
        // JWT-as-password bypasses cache entirely and uses token's own exp claim
        this.ttl = Duration.ofMillis(
            new Property(ArtipieProperties.AUTH_TIMEOUT).asLongOrDefault(300_000L)
        );
        
        EcsLogger.info("com.auto1.pantera.settings.cache")
            .message(String.format("Auth cache initialized - JWT-as-password bypasses cache: basicAuthTtl=%ds, jwtExpiry=%ds",
                this.ttl.toSeconds(), jwtSettings != null ? jwtSettings.expirySeconds() : -1))
            .eventCategory("cache")
            .eventAction("init")
            .log();
        
        // L1: Hot data cache for direct Basic Auth only
        final Duration l1Ttl = this.twoTier ? Duration.ofMinutes(5) : this.ttl;
        final int l1Size = this.twoTier ? 1000 : 10_000;
        
        this.cached = Caffeine.newBuilder()
            .maximumSize(l1Size)
            .expireAfterAccess(l1Ttl)
            .recordStats()
            .build();
    }
    
    /**
     * Ctor with configuration support.
     * @param origin Origin authentication
     * @param serverYaml Server configuration YAML
     */
    public CachedUsers(final Authentication origin, final YamlMapping serverYaml) {
        this(origin, serverYaml, null);
    }
    
    /**
     * Ctor with configuration and Valkey support.
     * @param origin Origin authentication
     * @param serverYaml Server configuration YAML
     * @param valkey Valkey connection for L2 cache
     */
    public CachedUsers(final Authentication origin, final YamlMapping serverYaml, final ValkeyConnection valkey) {
        this.origin = origin;
        final CacheConfig config = CacheConfig.from(serverYaml, "auth");
        this.twoTier = (valkey != null && config.valkeyEnabled());
        this.l2 = this.twoTier ? valkey.async() : null;
        // Use l2Ttl for L2 storage, main ttl for single-tier
        this.ttl = this.twoTier ? config.l2Ttl() : config.ttl();
        
        // L1: Hot data cache - use configured TTLs
        final Duration l1Ttl = this.twoTier ? config.l1Ttl() : config.ttl();
        final int l1Size = this.twoTier ? config.l1MaxSize() : config.maxSize();
        
        this.cached = Caffeine.newBuilder()
            .maximumSize(l1Size)
            .expireAfterAccess(l1Ttl)
            .recordStats()
            .evictionListener(this::onEviction)
            .build();
    }

    /**
     * Ctor.
     * @param origin Origin authentication
     * @param cache Cache for users
     */
    CachedUsers(
        final Authentication origin,
        final Cache<String, Optional<AuthUser>> cache
    ) {
        this.cached = cache;
        this.origin = origin;
        this.twoTier = false;  // Single-tier only
        this.l2 = null;
        this.ttl = Duration.ofMinutes(5);
    }

    @Override
    public Optional<AuthUser> user(final String user, final String pass) {
        return new UncheckedIOScalar<>(
            () -> {
                // JWT-as-password: Skip cache, validate directly.
                // JWT tokens have their own expiry (exp claim), caching would
                // override that expiry. Also, JWT validation is O(1) - no need to cache.
                if (looksLikeJwt(pass)) {
                    // JWT tokens bypass cache - validated directly using exp claim
                    return this.origin.user(user, pass);
                }
                
                // SECURITY: Use hashed key to prevent password exposure
                final String key = this.secureKey(user, pass);
                final long l1StartNanos = System.nanoTime();
                
                // L1: Check in-memory cache (fast, non-blocking)
                final Optional<AuthUser> l1Result = this.cached.getIfPresent(key);
                final long l1DurationMs = (System.nanoTime() - l1StartNanos) / 1_000_000;

                if (l1Result != null) {
                    if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                        com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheHit("auth", "l1");
                        com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                            .recordCacheOperationDuration("auth", "l1", "get", l1DurationMs);
                    }
                    return l1Result;
                }

                // L1 MISS
                if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                    com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheMiss("auth", "l1");
                    com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                        .recordCacheOperationDuration("auth", "l1", "get", l1DurationMs);
                }
                
                // PERFORMANCE: Skip L2 sync check to avoid blocking auth thread
                // L2 will warm L1 in background via async promotion
                // Note: origin.user() is already blocking, so L2 block would add latency
                
                // Compute from origin (synchronous)
                final Optional<AuthUser> result = this.origin.user(user, pass);

                // Only cache successful auth - don't cache failures
                // This prevents caching MFA failures that might succeed on retry
                if (result.isPresent()) {
                    // Cache in L1
                    final long putStartNanos = System.nanoTime();
                    this.cached.put(key, result);
                    final long putDurationMs = (System.nanoTime() - putStartNanos) / 1_000_000;

                    if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                        com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                            .recordCacheOperationDuration("auth", "l1", "put", putDurationMs);
                    }

                    // Cache in L2 (if enabled) - fire and forget
                    if (this.twoTier) {
                        // Key is already hashed - safe for Redis storage
                        final String redisKey = "auth:" + key;
                        final byte[] value = serializeUser(result);
                        this.l2.setex(redisKey, this.ttl.getSeconds(), value);
                    }
                }

                return result;
            }
        ).value();
    }
    
    /**
     * Async user lookup with L2 check.
     * Use this for background warming or non-critical auth checks.
     * 
     * @param user Username
     * @param pass Password
     * @return Future with authenticated user
     */
    public CompletableFuture<Optional<AuthUser>> userAsync(final String user, final String pass) {
        // SECURITY: Use hashed key to prevent password exposure
        final String key = this.secureKey(user, pass);
        
        // L1: Check in-memory cache
        final long l1StartNanos = System.nanoTime();
        final Optional<AuthUser> l1Result = this.cached.getIfPresent(key);
        final long l1DurationMs = (System.nanoTime() - l1StartNanos) / 1_000_000;

        if (l1Result != null) {
            if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheHit("auth", "l1");
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordCacheOperationDuration("auth", "l1", "get", l1DurationMs);
            }
            return CompletableFuture.completedFuture(l1Result);
        }

        // L1 MISS
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheMiss("auth", "l1");
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordCacheOperationDuration("auth", "l1", "get", l1DurationMs);
        }
        
        // L2: Check Valkey asynchronously (if enabled)
        if (this.twoTier) {
            final String redisKey = "auth:" + key;
            final long l2StartNanos = System.nanoTime();

            return this.l2.get(redisKey)
                .toCompletableFuture()
                .orTimeout(100, TimeUnit.MILLISECONDS)
                .exceptionally(err -> {
                    // L2 error - treat as miss
                    if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                        com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheMiss("auth", "l2");
                    }
                    return null;
                })
                .thenCompose(l2Bytes -> {
                    final long l2DurationMs = (System.nanoTime() - l2StartNanos) / 1_000_000;

                    if (l2Bytes != null) {
                        // L2 HIT: Deserialize and promote to L1
                        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                            com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheHit("auth", "l2");
                            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                                .recordCacheOperationDuration("auth", "l2", "get", l2DurationMs);
                        }

                        final Optional<AuthUser> result = deserializeUser(l2Bytes);
                        this.cached.put(key, result);



                        return CompletableFuture.completedFuture(result);
                    }

                    // L2 MISS: Fetch from origin (sync, then wrap in CompletableFuture)
                    if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                        com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheMiss("auth", "l2");
                        com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                            .recordCacheOperationDuration("auth", "l2", "get", l2DurationMs);
                    }

                    return CompletableFuture.supplyAsync(() -> {
                        final Optional<AuthUser> result = this.origin.user(user, pass);
                        // Only cache successful auth
                        if (result.isPresent()) {
                            this.cached.put(key, result);
                            final byte[] value = serializeUser(result);
                            this.l2.setex(redisKey, this.ttl.getSeconds(), value);
                        }
                        return result;
                    });
                });
        }

        // No L2: Fetch from origin (sync, then wrap in CompletableFuture)
        return CompletableFuture.supplyAsync(() -> {
            final Optional<AuthUser> result = this.origin.user(user, pass);
            // Only cache successful auth
            if (result.isPresent()) {
                this.cached.put(key, result);
            }
            return result;
        });
    }
    
    /**
     * Create secure cache key from credentials.
     * SECURITY: Uses SHA-256 to prevent password exposure in Redis keys.
     * @param user Username
     * @param pass Password
     * @return Hashed key safe for storage
     */
    private String secureKey(final String user, final String pass) {
        // Hash credentials to prevent password exposure
        // Format: SHA-256(username:password)
        final String combined = String.format("%s:%s", user, pass);
        return DigestUtils.sha256Hex(combined);
    }
    
    /**
     * Serialize AuthUser to byte array.
     */
    private byte[] serializeUser(final Optional<AuthUser> user) {
        if (user.isEmpty()) {
            return new byte[]{0};  // Empty marker
        }
        final String name = user.get().name();
        final byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocate(1 + nameBytes.length);
        buffer.put((byte) 1);  // Present marker
        buffer.put(nameBytes);
        return buffer.array();
    }
    
    /**
     * Deserialize AuthUser from byte array.
     */
    private Optional<AuthUser> deserializeUser(final byte[] bytes) {
        if (bytes == null || bytes.length < 1 || bytes[0] == 0) {
            return Optional.empty();
        }
        final byte[] nameBytes = new byte[bytes.length - 1];
        System.arraycopy(bytes, 1, nameBytes, 0, nameBytes.length);
        final String name = new String(nameBytes, StandardCharsets.UTF_8);
        return Optional.of(new AuthUser(name, "cached"));
    }

    /**
     * Check if password looks like a JWT token.
     * JWT tokens have format: header.payload.signature (base64url encoded).
     * @param password Password to check
     * @return True if it looks like a JWT
     */
    private static boolean looksLikeJwt(final String password) {
        if (password == null || password.length() < 20) {
            return false;
        }
        // JWT always starts with "eyJ" (base64 of '{"')
        // and has exactly 2 dots separating 3 parts
        if (!password.startsWith("eyJ")) {
            return false;
        }
        int dots = 0;
        for (int i = 0; i < password.length(); i++) {
            if (password.charAt(i) == '.') {
                dots++;
            }
        }
        return dots == 2;
    }

    @Override
    public String toString() {
        return String.format(
            "%s(size=%d),origin=%s",
            this.getClass().getSimpleName(), this.cached.estimatedSize(),
            this.origin.toString()
        );
    }

    @Override
    public void invalidate(final String key) {
        this.cached.invalidate(key);
    }

    @Override
    public void invalidateAll() {
        this.cached.invalidateAll();
    }

    /**
     * Handle auth cache eviction - record metrics.
     * @param key Cache key
     * @param user Auth user
     * @param cause Eviction cause
     */
    private void onEviction(
        final String key,
        final Optional<AuthUser> user,
        final com.github.benmanes.caffeine.cache.RemovalCause cause
    ) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordCacheEviction("auth", "l1", cause.toString().toLowerCase());
        }
    }
}
