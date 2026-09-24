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
package com.auto1.pantera.auth;

import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.settings.policy.LoginThrottleConfig;
import com.auto1.pantera.settings.policy.LoginThrottleSettingsLoader;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Login attempt throttle (SecOps import-misc, hardened for B16).
 *
 * <p>Every password login is admitted through {@link #admit(String, String)}
 * <em>before</em> the credential check runs, and the attempt is counted at
 * that moment (a success then clears it). Counting after the asynchronous
 * check let concurrent requests all pass the gate before any failure was
 * recorded. Two budgets apply within the configured window:</p>
 * <ul>
 *   <li>{@code max_failures} per (username, client address) pair;</li>
 *   <li>{@value #USER_BUDGET_FACTOR} x {@code max_failures} per username from
 *       any address, so rotating the client address does not give unlimited
 *       guesses.</li>
 * </ul>
 *
 * <p>The client address must come from a trusted source (the TCP peer, or a
 * forwarding header only behind a declared trusted proxy); the caller
 * resolves it. One instance is shared by every API verticle in the process,
 * so the limit does not multiply with the verticle count. State is per node
 * and bounded (at most {@value #MAX_KEYS} tracked keys).</p>
 *
 * @since 2.2.9
 */
public final class LoginThrottle {

    /**
     * Username-wide budget as a multiple of the per-pair limit.
     */
    static final int USER_BUDGET_FACTOR = 4;

    /**
     * Upper bound on tracked keys (memory bound under attack on many keys).
     */
    private static final long MAX_KEYS = 100_000L;

    /**
     * Nanoseconds per second.
     */
    private static final long NANOS = 1_000_000_000L;

    private final Supplier<LoginThrottleConfig> config;

    private final LongSupplier clock;

    private final Cache<String, Attempt> attempts;

    /**
     * Production ctor: thresholds from the DB-backed admin setting
     * ({@code login_throttle_*}), re-read on every check.
     */
    public LoginThrottle() {
        this(LoginThrottleSettingsLoader.activeSupplier(), System::nanoTime);
    }

    /**
     * Fixed thresholds (tests).
     * @param maxFailures Attempts before lockout
     * @param window Lockout window
     * @param clock Nano-time source
     */
    public LoginThrottle(final int maxFailures, final Duration window, final LongSupplier clock) {
        this(
            LoginThrottle.fixed(new LoginThrottleConfig(maxFailures, Math.toIntExact(window.toSeconds()))),
            clock
        );
    }

    /**
     * The single field-initializing ctor.
     * @param config Live thresholds, read on every decision
     * @param clock Nano-time source
     */
    public LoginThrottle(final Supplier<LoginThrottleConfig> config, final LongSupplier clock) {
        this.config = config;
        this.clock = clock;
        this.attempts = Caffeine.newBuilder().maximumSize(LoginThrottle.MAX_KEYS).build();
    }

    private static Supplier<LoginThrottleConfig> fixed(final LoginThrottleConfig config) {
        return () -> config;
    }

    /**
     * Admit a login attempt, counting it before the credential check.
     *
     * @param username Claimed username (may be null)
     * @param client Trusted client address (may be null)
     * @return Empty when admitted; otherwise the seconds until retry
     */
    public synchronized OptionalLong admit(final String username, final String client) {
        final long now = this.clock.getAsLong();
        final LoginThrottleConfig current = this.config.get();
        final long window = current.window().toNanos();
        final int pairLimit = current.maxFailures();
        final int userLimit = (int) Math.min(
            Integer.MAX_VALUE, (long) pairLimit * LoginThrottle.USER_BUDGET_FACTOR
        );
        final Attempt pair = this.live(LoginThrottle.pairKey(username, client), now, window);
        final Attempt user = this.live(LoginThrottle.userKey(username), now, window);
        final OptionalLong verdict;
        if (pair.count >= pairLimit) {
            verdict = OptionalLong.of(pair.refuse(username, client, true, now, window));
        } else if (user.count >= userLimit) {
            verdict = OptionalLong.of(user.refuse(username, client, false, now, window));
        } else {
            pair.count += 1;
            user.count += 1;
            verdict = OptionalLong.empty();
        }
        return verdict;
    }

    /**
     * A successful login: clear the pair's counter and give back the
     * username-wide unit this attempt consumed.
     *
     * @param username Username
     * @param client Client address
     */
    public synchronized void recordSuccess(final String username, final String client) {
        this.attempts.invalidate(LoginThrottle.pairKey(username, client));
        final Attempt user = this.attempts.getIfPresent(LoginThrottle.userKey(username));
        if (user != null && user.count > 0) {
            user.count -= 1;
        }
    }

    /**
     * The live (in-window) attempt record for a key, starting a new window
     * when absent or lapsed.
     */
    private Attempt live(final String key, final long now, final long window) {
        Attempt att = this.attempts.getIfPresent(key);
        if (att == null || now - att.firstNanos > window) {
            att = new Attempt(now);
            this.attempts.put(key, att);
        }
        return att;
    }

    private static String pairKey(final String username, final String client) {
        return "p:" + username + '|' + client;
    }

    private static String userKey(final String username) {
        return "u:" + username;
    }

    private static void logLockout(final String username, final String client, final boolean pair) {
        EcsLogger.warn("com.auto1.pantera.auth")
            .message(pair
                ? "Login refused: attempt limit reached for this user and client address; further attempts are refused until the window ends"
                : "Login refused: attempt limit reached for this user from all addresses; further attempts are refused until the window ends")
            .eventCategory("authentication")
            .eventAction("login_throttled")
            .eventOutcome("failure")
            .field("user.name", username)
            .field("client.ip", client)
            .field("log.source", "application")
            .log();
    }

    /**
     * One key's window. Mutated only under the throttle's lock.
     */
    private static final class Attempt {
        private final long firstNanos;
        private int count;

        /**
         * Whether this window's lockout has already been logged.
         */
        private boolean logged;

        Attempt(final long firstNanos) {
            this.firstNanos = firstNanos;
        }

        /**
         * Refuse an attempt: log the lockout on its first refusal only (so
         * the log records a refusal, once per lockout window, R04) and
         * answer the seconds until retry.
         */
        long refuse(final String username, final String client, final boolean pair,
            final long now, final long window) {
            if (!this.logged) {
                this.logged = true;
                LoginThrottle.logLockout(username, client, pair);
            }
            return this.retryAfterSeconds(now, window);
        }

        long retryAfterSeconds(final long now, final long window) {
            final long remaining = this.firstNanos + window - now;
            return Math.max(1L, (remaining + LoginThrottle.NANOS - 1) / LoginThrottle.NANOS);
        }
    }
}
