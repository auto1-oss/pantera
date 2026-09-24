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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code login_throttled} warning records a refusal: it is written on the
 * first refused attempt of a lockout, once, and never for an admitted attempt
 * (R04: it used to be written when the last allowed attempt was admitted).
 *
 * @since 2.2.9
 */
final class LoginThrottleLogTest {

    private static final String CAP = "LoginThrottleLogCap";

    private static final String AUTH_LOG = "com.auto1.pantera.auth";

    private Capture capture;

    @BeforeEach
    void setUp() {
        this.capture = new Capture(LoginThrottleLogTest.CAP);
        this.capture.start();
        Configurator.setLevel(LoginThrottleLogTest.AUTH_LOG, Level.WARN);
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final Configuration cfg = ctx.getConfiguration();
        cfg.addAppender(this.capture);
        cfg.getLoggerConfig(LoginThrottleLogTest.AUTH_LOG).addAppender(this.capture, null, null);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().getLoggerConfig(LoginThrottleLogTest.AUTH_LOG)
            .removeAppender(LoginThrottleLogTest.CAP);
        this.capture.stop();
        ctx.updateLoggers();
    }

    @Test
    void admittedAttemptsLogNoLockout() {
        final LoginThrottle throttle = LoginThrottleLogTest.throttle();
        for (int idx = 0; idx < 3; idx += 1) {
            throttle.admit("alice", "1.2.3.4");
        }
        MatcherAssert.assertThat(this.capture.count(), new IsEqual<>(0));
    }

    @Test
    void firstRefusalLogsTheLockoutOnce() {
        final LoginThrottle throttle = LoginThrottleLogTest.throttle();
        for (int idx = 0; idx < 3; idx += 1) {
            throttle.admit("alice", "1.2.3.4");
        }
        throttle.admit("alice", "1.2.3.4");
        MatcherAssert.assertThat(
            "the first refused attempt is logged",
            this.capture.count(), new IsEqual<>(1)
        );
        throttle.admit("alice", "1.2.3.4");
        throttle.admit("alice", "1.2.3.4");
        MatcherAssert.assertThat(
            "later refusals in the same lockout are not logged again",
            this.capture.count(), new IsEqual<>(1)
        );
    }

    @Test
    void successOnTheLastAllowedAttemptLogsNothing() {
        final LoginThrottle throttle = LoginThrottleLogTest.throttle();
        for (int idx = 0; idx < 3; idx += 1) {
            throttle.admit("bob", "1.2.3.4");
        }
        throttle.recordSuccess("bob", "1.2.3.4");
        MatcherAssert.assertThat(this.capture.count(), new IsEqual<>(0));
    }

    private static LoginThrottle throttle() {
        return new LoginThrottle(3, Duration.ofMinutes(15), new AtomicLong()::get);
    }

    /**
     * Captures WARN records of the auth logger.
     */
    private static final class Capture extends AbstractAppender {

        private final List<String> warns = new ArrayList<>();

        Capture(final String name) {
            super(name, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(final LogEvent event) {
            if (LoginThrottleLogTest.AUTH_LOG.equals(event.getLoggerName())
                && event.getLevel().isMoreSpecificThan(Level.WARN)) {
                synchronized (this.warns) {
                    this.warns.add(event.getMessage().getFormattedMessage());
                }
            }
        }

        int count() {
            synchronized (this.warns) {
                return this.warns.size();
            }
        }
    }
}
