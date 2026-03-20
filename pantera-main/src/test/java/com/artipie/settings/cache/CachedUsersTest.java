/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.settings.cache;

import com.artipie.http.auth.AuthUser;
import com.artipie.http.auth.Authentication;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
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
