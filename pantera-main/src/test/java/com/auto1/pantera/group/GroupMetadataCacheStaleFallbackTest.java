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
package com.auto1.pantera.group;

import com.github.benmanes.caffeine.cache.Cache;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Tests the 4-step graceful-degradation read path of
 * {@link GroupMetadataCache#getStaleWithFallback(String)}:
 * stale-L1 &rarr; stale-L2 &rarr; expired-primary-L1 &rarr; miss.
 *
 * <p>Stale-L2 and the primary-L1 peek are exercised via reflection because
 * there is no injectable L2 hook on the public API; the test keeps the
 * class's public surface untouched while still covering each branch.
 */
final class GroupMetadataCacheStaleFallbackTest {

    @Test
    void staleL1Hit() throws Exception {
        final GroupMetadataCache cache = new GroupMetadataCache("g1");
        final String path = "/com/foo/maven-metadata.xml";
        final byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        cache.put(path, data);

        final Optional<byte[]> res = cache.getStaleWithFallback(path)
            .get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "stale-L1 hit returns the cached bytes",
            res.isPresent(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            new String(res.get(), StandardCharsets.UTF_8),
            Matchers.equalTo("hello")
        );
    }

    @Test
    void staleL2HitPromotesToL1() throws Exception {
        final GroupMetadataCache cache = new GroupMetadataCache("g2");
        final String path = "/com/bar/maven-metadata.xml";
        final byte[] data = "from-l2".getBytes(StandardCharsets.UTF_8);

        // Drop any stale-L1 entry and install a stub stale-L2 that hits.
        clearStaleL1(cache);
        final AtomicReference<String> lastGetKey = new AtomicReference<>();
        final RedisAsyncCommands<String, byte[]> stub = newStubAsync(
            key -> {
                lastGetKey.set(key);
                return data;
            }
        );
        enableStaleL2(cache, stub);

        final Optional<byte[]> res = cache.getStaleWithFallback(path)
            .get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "stale-L2 hit returns the bytes",
            res.isPresent(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            new String(res.get(), StandardCharsets.UTF_8),
            Matchers.equalTo("from-l2")
        );
        MatcherAssert.assertThat(
            "stale-L2 key is the stale-namespaced key",
            lastGetKey.get(),
            Matchers.equalTo("maven:group:metadata:stale:g2:" + path)
        );
        // Promote to stale-L1 — second call should hit L1 without calling L2.
        lastGetKey.set(null);
        final Optional<byte[]> second = cache.getStaleWithFallback(path)
            .get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "second call hits promoted stale-L1",
            second.isPresent() && lastGetKey.get() == null,
            Matchers.is(true)
        );
    }

    @Test
    void expiredPrimaryFallback() throws Exception {
        final GroupMetadataCache cache = new GroupMetadataCache("g3");
        final String path = "/com/baz/maven-metadata.xml";
        final byte[] data = "expired-primary".getBytes(StandardCharsets.UTF_8);
        cache.put(path, data);
        // Wipe the stale-L1 entry — primary L1 still holds the data.
        clearStaleL1(cache);

        final Optional<byte[]> res = cache.getStaleWithFallback(path)
            .get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "expired-primary peek returns data",
            res.isPresent(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            new String(res.get(), StandardCharsets.UTF_8),
            Matchers.equalTo("expired-primary")
        );
    }

    @Test
    void allTiersMissReturnsEmpty() throws Exception {
        final GroupMetadataCache cache = new GroupMetadataCache("g4");
        final Optional<byte[]> res = cache
            .getStaleWithFallback("/com/missing/maven-metadata.xml")
            .get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "miss returns empty (no throw)",
            res.isPresent(),
            Matchers.is(false)
        );
    }

    // -------------------------------------------------------------
    // Reflection helpers — keep the public API untouched.
    // -------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void clearStaleL1(final GroupMetadataCache cache) throws Exception {
        final Field f = GroupMetadataCache.class.getDeclaredField("lastKnownGoodL1");
        f.setAccessible(true);
        final Cache<String, byte[]> c = (Cache<String, byte[]>) f.get(cache);
        c.invalidateAll();
    }

    private static void enableStaleL2(
        final GroupMetadataCache cache,
        final RedisAsyncCommands<String, byte[]> stub
    ) throws Exception {
        final Field two = GroupMetadataCache.class.getDeclaredField("staleTwoTier");
        two.setAccessible(true);
        two.setBoolean(cache, true);
        final Field l2 = GroupMetadataCache.class.getDeclaredField("staleL2");
        l2.setAccessible(true);
        l2.set(cache, stub);
    }

    /**
     * Minimal {@link RedisAsyncCommands} proxy that only handles {@code get}.
     * All other calls fail loudly so we notice if the production path
     * reaches a non-stubbed method.
     */
    @SuppressWarnings("unchecked")
    private static RedisAsyncCommands<String, byte[]> newStubAsync(
        final java.util.function.Function<String, byte[]> getImpl
    ) {
        final InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(
                final Object proxy,
                final Method method,
                final Object[] args
            ) {
                if ("get".equals(method.getName()) && args != null && args.length == 1) {
                    final byte[] value = getImpl.apply((String) args[0]);
                    return completedRedisFuture(value);
                }
                throw new UnsupportedOperationException(
                    "stub RedisAsyncCommands does not implement " + method.getName()
                );
            }
        };
        return (RedisAsyncCommands<String, byte[]>) Proxy.newProxyInstance(
            RedisAsyncCommands.class.getClassLoader(),
            new Class<?>[] { RedisAsyncCommands.class },
            handler
        );
    }

    /**
     * Build a {@link RedisFuture} that wraps a completed
     * {@link CompletableFuture} — we only rely on
     * {@link RedisFuture#toCompletableFuture()} downstream.
     */
    @SuppressWarnings("unchecked")
    private static RedisFuture<byte[]> completedRedisFuture(final byte[] value) {
        final CompletableFuture<byte[]> cf = CompletableFuture.completedFuture(value);
        final InvocationHandler handler = (proxy, method, args) -> {
            if ("toCompletableFuture".equals(method.getName())) {
                return cf;
            }
            // Delegate anything the production code might invoke to the CF.
            try {
                final Method cfMethod = CompletableFuture.class.getMethod(
                    method.getName(),
                    method.getParameterTypes()
                );
                return cfMethod.invoke(cf, args);
            } catch (final NoSuchMethodException ex) {
                throw new UnsupportedOperationException(
                    "stub RedisFuture does not implement " + method.getName()
                );
            }
        };
        return (RedisFuture<byte[]>) Proxy.newProxyInstance(
            RedisFuture.class.getClassLoader(),
            new Class<?>[] { RedisFuture.class },
            handler
        );
    }
}
