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
package com.auto1.pantera.settings.cache;

import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.LoginSource;
import com.auto1.pantera.http.auth.PasswordCheck;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test case for {@link CachedUsers}.
 *
 * @since 0.22
 */
final class CachedUsersTest {

    /**
     * Test cache.
     */
    private Cache<String, Optional<AuthUser>> cache;

    /**
     * Test users.
     */
    private CachedUsers users;

    /**
     * Test authentication.
     */
    private FakeAuth auth;

    @BeforeEach
    void init() {
        this.cache = Caffeine.newBuilder().build();
        this.auth = new FakeAuth();
        this.users = new CachedUsers(this.auth, this.cache);
    }

    @Test
    void authenticatesAndCachesResult() {
        MatcherAssert.assertThat(
            "Jane was authenticated on the first call",
            this.users.user("jane", "any").isPresent()
        );
        MatcherAssert.assertThat(
            "Cache size should be 1",
            this.cache.estimatedSize(),
            new IsEqual<>(1L)
        );
        MatcherAssert.assertThat(
            "Jane was authenticated on the second call",
            this.users.user("jane", "any").isPresent()
        );
        MatcherAssert.assertThat(
            "Cache size should be 1",
            this.cache.estimatedSize(),
            new IsEqual<>(1L)
        );
        MatcherAssert.assertThat(
            "Authenticate method should be called only once",
            this.auth.cnt.get(),
            new IsEqual<>(1)
        );
    }

    @Test
    void invalidateByKeyFlushesEntireCache() {
        // Populate the cache with a successful auth
        this.users.user("jane", "any");
        MatcherAssert.assertThat(
            "Cache should have 1 entry after a successful auth",
            this.cache.estimatedSize(),
            new IsEqual<>(1L)
        );
        // CachedUsers cache key is SHA-256(user:pass), so callers have no
        // way to target a specific entry by plain-text key. invalidate(k)
        // must flush the entire cache as the only safe behavior. Previously
        // this was a no-op (the bug behind "old password still works after
        // change") because callers passed usernames that never matched the
        // SHA-256 keys.
        this.users.invalidate("jane");
        MatcherAssert.assertThat(
            "Cache must be fully flushed after invalidate(key)",
            this.cache.estimatedSize(),
            new IsEqual<>(0L)
        );
    }

    @Test
    void invalidateByUsernameFlushesEntireCache() {
        // Populate for multiple users
        this.users.user("jane", "pass1");
        this.users.user("jane", "pass2");
        MatcherAssert.assertThat(
            "Cache should have 2 entries (different passwords → different keys)",
            this.cache.estimatedSize(),
            new IsEqual<>(2L)
        );
        this.users.invalidateByUsername("jane");
        MatcherAssert.assertThat(
            "Cache must be fully flushed",
            this.cache.estimatedSize(),
            new IsEqual<>(0L)
        );
    }

    @Test
    void nextAuthHitsOriginAfterInvalidate() {
        this.users.user("jane", "any");
        MatcherAssert.assertThat(
            "First auth hit the origin once",
            this.auth.cnt.get(),
            new IsEqual<>(1)
        );
        // Cached hit — origin not called
        this.users.user("jane", "any");
        MatcherAssert.assertThat(
            "Second auth served from cache",
            this.auth.cnt.get(),
            new IsEqual<>(1)
        );
        // After invalidate, the next call MUST go to origin.
        this.users.invalidate("jane");
        this.users.user("jane", "any");
        MatcherAssert.assertThat(
            "Post-invalidate auth re-consults the origin",
            this.auth.cnt.get(),
            new IsEqual<>(2)
        );
    }

    @Test
    void invalidateAllFlushesEntireCache() {
        this.users.user("jane", "pass1");
        MatcherAssert.assertThat(this.cache.estimatedSize(), new IsEqual<>(1L));
        this.users.invalidateAll();
        MatcherAssert.assertThat(
            "invalidateAll must empty the cache",
            this.cache.estimatedSize(),
            new IsEqual<>(0L)
        );
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "VALKEY_HOST", matches = ".+"
    )
    void invalidateAllFlushesTheSharedTier() throws Exception {
        try (com.auto1.pantera.cache.ValkeyConnection conn =
            new com.auto1.pantera.cache.ValkeyConnection(
                System.getenv("VALKEY_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("VALKEY_PORT", "6379")),
                java.time.Duration.ofSeconds(2)
            )) {
            final java.util.List<String> keys = new java.util.ArrayList<>();
            for (int idx = 0; idx < 50; idx = idx + 1) {
                final String key = "auth:qa-ct-" + java.util.UUID.randomUUID();
                conn.async().setex(key, 120L, new byte[] {1})
                    .get(2, java.util.concurrent.TimeUnit.SECONDS);
                keys.add(key);
            }
            new CachedUsers(this.auth, conn).invalidateAll();
            MatcherAssert.assertThat(
                conn.async().exists(keys.toArray(new String[0]))
                    .get(2, java.util.concurrent.TimeUnit.SECONDS),
                new IsEqual<>(0L)
            );
        }
    }

    @Test
    void doesNotCacheFailedAuth() {
        MatcherAssert.assertThat(
            "David was not authenticated on the first call",
            this.users.user("David", "any").isEmpty()
        );
        MatcherAssert.assertThat(
            "Olga was not authenticated on the first call",
            this.users.user("Olga", "any").isEmpty()
        );
        MatcherAssert.assertThat(
            "Cache size should be 0 - failed auth should not be cached",
            this.cache.estimatedSize(),
            new IsEqual<>(0L)
        );
        MatcherAssert.assertThat(
            "David was not authenticated on the second call",
            this.users.user("David", "any").isEmpty()
        );
        MatcherAssert.assertThat(
            "Olga was not authenticated on the second call",
            this.users.user("Olga", "any").isEmpty()
        );
        MatcherAssert.assertThat(
            "Cache size should still be 0",
            this.cache.estimatedSize(),
            new IsEqual<>(0L)
        );
        MatcherAssert.assertThat(
            "Authenticate method should be called 4 times (no caching for failures)",
            this.auth.cnt.get(),
            new IsEqual<>(4)
        );
    }

    @Test
    void signInOnTheFormIsNeitherAnsweredFromMemoryNorRemembered() {
        // A person signs in on the form, where the identity provider asks
        // for a second factor: a remembered login would skip it.
        final LoginSource form = new LoginSource("10.0.0.1", "Mozilla/5.0", "ui");
        new PasswordCheck(this.users, form).now("jane", "any");
        new PasswordCheck(this.users, form).now("jane", "any");
        MatcherAssert.assertThat(
            "the provider is asked each time, and nothing is remembered",
            List.of(this.auth.cnt.get(), (int) this.cache.estimatedSize()),
            new IsEqual<>(List.of(2, 0))
        );
    }

    @Test
    void repositoryRequestsAreStillAnsweredFromMemory() {
        final LoginSource maven = new LoginSource("10.0.0.1", "Apache-Maven/3.9", "repository");
        new PasswordCheck(this.users, maven).now("jane", "any");
        new PasswordCheck(this.users, maven).now("jane", "any");
        MatcherAssert.assertThat(this.auth.cnt.get(), new IsEqual<>(1));
    }

    /**
     * Fake authentication: returns "jane" when username is jane, empty otherwise.
     * @since 0.27
     */
    final class FakeAuth implements Authentication {

        /**
         * Method call count.
         */
        private final AtomicInteger cnt = new AtomicInteger();

        @Override
        public Optional<AuthUser> user(final String name, final String pswd) {
            this.cnt.incrementAndGet();
            final Optional<AuthUser> res;
            if (name.equals("jane")) {
                res = Optional.of(new AuthUser(name, "test"));
            } else {
                res = Optional.empty();
            }
            return res;
        }
    }

}
