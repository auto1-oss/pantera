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
package com.auto1.pantera.settings.policy;

import java.time.Duration;

/**
 * Validated login-throttle thresholds: failures per (user, client IP) key
 * before further password logins are refused, and the window they count in.
 *
 * @param maxFailures Failures before lockout, at least 1
 * @param windowSeconds Window length in seconds, at least 1
 * @since 2.2.9
 */
public record LoginThrottleConfig(int maxFailures, int windowSeconds) {

    /**
     * Default failures before lockout.
     */
    public static final int DEFAULT_MAX_FAILURES = 5;

    /**
     * Default window: fifteen minutes.
     */
    public static final int DEFAULT_WINDOW_SECONDS = 900;

    /**
     * Validating ctor.
     * @param maxFailures Failures before lockout, at least 1
     * @param windowSeconds Window length in seconds, at least 1
     */
    public LoginThrottleConfig {
        if (maxFailures < 1) {
            throw new IllegalArgumentException("login_throttle_max_failures must be at least 1");
        }
        if (windowSeconds < 1) {
            throw new IllegalArgumentException("login_throttle_window_seconds must be at least 1");
        }
    }

    /**
     * Documented defaults.
     * @return Config
     */
    public static LoginThrottleConfig defaults() {
        return new LoginThrottleConfig(DEFAULT_MAX_FAILURES, DEFAULT_WINDOW_SECONDS);
    }

    /**
     * Window as a duration.
     * @return Duration
     */
    public Duration window() {
        return Duration.ofSeconds(this.windowSeconds);
    }
}
