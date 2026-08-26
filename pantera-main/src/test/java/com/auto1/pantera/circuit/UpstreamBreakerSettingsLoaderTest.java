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
package com.auto1.pantera.circuit;

import com.auto1.pantera.db.dao.AuthSettingsDao;
import com.auto1.pantera.http.client.circuitbreaker.CircuitBreakerConfig;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Tests for {@link UpstreamBreakerSettingsLoader}: DB-less fallback to
 * hardcoded defaults, and the uninstalled {@code activeSupplier} path.
 * DB-backed resolution shares the exact resolve chain with the
 * long-standing {@link CircuitBreakerSettingsLoader} and is covered by
 * the admin-endpoint round-trip.
 *
 * @since 2.2.0
 */
final class UpstreamBreakerSettingsLoaderTest {

    @AfterEach
    void tearDown() {
        UpstreamBreakerSettingsLoader.uninstall();
    }

    @Test
    void activeSupplierWithoutInstallReturnsDefaults() {
        final CircuitBreakerConfig resolved =
            UpstreamBreakerSettingsLoader.activeSupplier().get();
        final CircuitBreakerConfig defaults = CircuitBreakerConfig.defaults();
        MatcherAssert.assertThat(
            "uninstalled supplier resolves the default failure-rate threshold",
            resolved.failureRateThreshold(),
            new IsEqual<>(defaults.failureRateThreshold())
        );
        MatcherAssert.assertThat(
            "uninstalled supplier resolves the default minimum calls",
            resolved.minimumCalls(), new IsEqual<>(defaults.minimumCalls())
        );
        MatcherAssert.assertThat(
            "uninstalled supplier resolves the default backoff cap",
            resolved.maxBackoff(), new IsEqual<>(defaults.maxBackoff())
        );
    }

    @Test
    void nullDaoLoaderFallsBackToDefaults() {
        final UpstreamBreakerSettingsLoader loader =
            new UpstreamBreakerSettingsLoader(null);
        final CircuitBreakerConfig resolved = loader.get();
        MatcherAssert.assertThat(
            "gate values fall back to defaults without a DB",
            resolved.minimumCalls(),
            new IsEqual<>(CircuitBreakerConfig.defaults().minimumCalls())
        );
        MatcherAssert.assertThat(
            "backoff seed falls back to defaults without a DB",
            resolved.seedBackoff(),
            new IsEqual<>(CircuitBreakerConfig.defaults().seedBackoff())
        );
    }

    @Test
    void installedSupplierIsInvalidatable() {
        UpstreamBreakerSettingsLoader.install(null);
        final UpstreamBreakerSettingsLoader loader =
            UpstreamBreakerSettingsLoader.installed();
        loader.get();
        loader.invalidate();
        MatcherAssert.assertThat(
            "invalidate reloads without error and still resolves",
            UpstreamBreakerSettingsLoader.activeSupplier().get().windowSeconds(),
            new IsEqual<>(CircuitBreakerConfig.defaults().windowSeconds())
        );
    }

    /**
     * Regression guard for the V136 seed-shadowing bug (WS8 fixwave-f): a
     * DB-backed deployment where V138 has removed the row an admin never
     * customised has an installed DAO whose {@code get(key)} genuinely
     * returns empty (not a {@code null} DAO -- that DB-less case is
     * {@link #dbLessInstallPathResolvesEnvOverDefault()} below). Before the
     * V138 fix, {@code V136__upstream_breaker_settings.sql} unconditionally
     * wrote {@code upstream_breaker_minimum_calls='10'}, so {@code
     * dao.get(key)} always returned a present row and the env tier below
     * was never consulted -- this test would have failed against that
     * migration's behaviour reproduced at the loader level.
     */
    @Test
    void daoReportsKeyAbsentEnvTierWinsOverDefault() {
        final UpstreamBreakerSettingsLoader loader = new UpstreamBreakerSettingsLoader(
            UpstreamBreakerSettingsLoaderTest.emptyRowDao(),
            Map.of("PANTERA_UPSTREAM_BREAKER_MINIMUM_CALLS", "42")::get
        );
        MatcherAssert.assertThat(loader.get().minimumCalls(), new IsEqual<>(42));
    }

    /**
     * Regression guard for the DB-less-boot half of the same bug: before
     * the fix, {@code VertxMain} only called {@code
     * UpstreamBreakerSettingsLoader.install} inside {@code
     * sharedDs.ifPresent(...)}, so a boot with no shared {@code
     * DataSource} never installed a loader at all and {@code
     * PANTERA_UPSTREAM_BREAKER_*} was never read -- {@code
     * activeSupplier()} fell straight to {@link
     * CircuitBreakerConfig#defaults()}. This drives the same {@code
     * install(null, envLookup)} shape the fixed {@code VertxMain} now
     * calls unconditionally, and would have failed before that fix.
     */
    @Test
    void dbLessInstallPathResolvesEnvOverDefault() {
        UpstreamBreakerSettingsLoader.install(
            null, Map.of("PANTERA_UPSTREAM_BREAKER_MINIMUM_CALLS", "42")::get
        );
        MatcherAssert.assertThat(
            UpstreamBreakerSettingsLoader.activeSupplier().get().minimumCalls(),
            new IsEqual<>(42)
        );
    }

    /**
     * Builds an {@link AuthSettingsDao} whose {@code get(key)} always
     * resolves empty, backed by dynamic-proxied JDK interfaces rather than
     * a real database -- the same {@link Proxy}-based faking technique
     * used in {@code ClientBaseUrlSettingsLoaderTest}. Doing this via the
     * actual {@link AuthSettingsDao} class (rather than a {@code null}
     * DAO) exercises the real {@code dao.get(key)} call path, which a
     * plain {@code null}-DAO test cannot.
     * @return DAO that reports every key absent, without touching a real DB
     */
    private static AuthSettingsDao emptyRowDao() {
        return new AuthSettingsDao(UpstreamBreakerSettingsLoaderTest.fakeDataSource());
    }

    private static DataSource fakeDataSource() {
        return UpstreamBreakerSettingsLoaderTest.proxy(DataSource.class, (target, method, args) ->
            "getConnection".equals(method.getName()) && method.getParameterCount() == 0
                ? UpstreamBreakerSettingsLoaderTest.fakeConnection()
                : null
        );
    }

    private static Connection fakeConnection() {
        return UpstreamBreakerSettingsLoaderTest.proxy(Connection.class, (target, method, args) ->
            "prepareStatement".equals(method.getName())
                ? UpstreamBreakerSettingsLoaderTest.fakePreparedStatement()
                : null
        );
    }

    private static PreparedStatement fakePreparedStatement() {
        return UpstreamBreakerSettingsLoaderTest.proxy(
            PreparedStatement.class, (target, method, args) ->
                "executeQuery".equals(method.getName())
                    ? UpstreamBreakerSettingsLoaderTest.fakeEmptyResultSet()
                    : null
        );
    }

    private static ResultSet fakeEmptyResultSet() {
        return UpstreamBreakerSettingsLoaderTest.proxy(ResultSet.class, (target, method, args) ->
            "next".equals(method.getName()) ? Boolean.FALSE : null
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(final Class<T> type, final InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
