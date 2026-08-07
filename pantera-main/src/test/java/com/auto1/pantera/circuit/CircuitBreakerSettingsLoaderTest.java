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
import com.auto1.pantera.http.timeout.AutoBlockSettings;
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
 * Tests for {@link CircuitBreakerSettingsLoader}: DB-less fallback to
 * hardcoded defaults, and the uninstalled {@code activeSupplier} path.
 * DB-backed resolution shares the exact resolve chain with
 * {@link UpstreamBreakerSettingsLoader} and is covered by the admin-endpoint
 * round-trip.
 *
 * @since 2.3.0
 */
final class CircuitBreakerSettingsLoaderTest {

    @AfterEach
    void tearDown() {
        CircuitBreakerSettingsLoader.uninstall();
    }

    @Test
    void activeSupplierWithoutInstallReturnsDefaults() {
        final AutoBlockSettings resolved =
            CircuitBreakerSettingsLoader.activeSupplier().get();
        final AutoBlockSettings defaults = AutoBlockSettings.defaults();
        MatcherAssert.assertThat(
            "uninstalled supplier resolves the default failure-rate threshold",
            resolved.failureRateThreshold(),
            new IsEqual<>(defaults.failureRateThreshold())
        );
        MatcherAssert.assertThat(
            "uninstalled supplier resolves the default minimum number of calls",
            resolved.minimumNumberOfCalls(), new IsEqual<>(defaults.minimumNumberOfCalls())
        );
        MatcherAssert.assertThat(
            "uninstalled supplier resolves the default max block duration",
            resolved.maxBlockDuration(), new IsEqual<>(defaults.maxBlockDuration())
        );
    }

    @Test
    void nullDaoLoaderFallsBackToDefaults() {
        final CircuitBreakerSettingsLoader loader =
            new CircuitBreakerSettingsLoader(null);
        final AutoBlockSettings resolved = loader.get();
        MatcherAssert.assertThat(
            "gate values fall back to defaults without a DB",
            resolved.minimumNumberOfCalls(),
            new IsEqual<>(AutoBlockSettings.defaults().minimumNumberOfCalls())
        );
        MatcherAssert.assertThat(
            "initial block duration falls back to defaults without a DB",
            resolved.initialBlockDuration(),
            new IsEqual<>(AutoBlockSettings.defaults().initialBlockDuration())
        );
    }

    @Test
    void installedSupplierIsInvalidatable() {
        CircuitBreakerSettingsLoader.install(null);
        final CircuitBreakerSettingsLoader loader =
            CircuitBreakerSettingsLoader.installed();
        loader.get();
        loader.invalidate();
        MatcherAssert.assertThat(
            "invalidate reloads without error and still resolves",
            CircuitBreakerSettingsLoader.activeSupplier().get().slidingWindowSeconds(),
            new IsEqual<>(AutoBlockSettings.defaults().slidingWindowSeconds())
        );
    }

    /**
     * Regression guard for the V122 seed-shadowing bug (WS8 fixwave-g): a
     * DB-backed deployment where V139 has removed the row an admin never
     * customised has an installed DAO whose {@code get(key)} genuinely
     * returns empty (not a {@code null} DAO -- that DB-less case is
     * {@link #dbLessInstallPathResolvesEnvOverDefault()} below). Before the
     * V139 fix, {@code V122__circuit_breaker_settings.sql} unconditionally
     * wrote {@code circuit_breaker_minimum_number_of_calls='20'}, so {@code
     * dao.get(key)} always returned a present row and the env tier below
     * was never consulted -- this test would have failed against that
     * migration's behaviour reproduced at the loader level.
     */
    @Test
    void daoReportsKeyAbsentEnvTierWinsOverDefault() {
        final CircuitBreakerSettingsLoader loader = new CircuitBreakerSettingsLoader(
            CircuitBreakerSettingsLoaderTest.emptyRowDao(),
            Map.of("PANTERA_CIRCUIT_BREAKER_MINIMUM_NUMBER_OF_CALLS", "42")::get
        );
        MatcherAssert.assertThat(loader.get().minimumNumberOfCalls(), new IsEqual<>(42));
    }

    /**
     * Regression guard for the DB-less-boot half of the same bug: before
     * the fix, {@code VertxMain} only called {@code
     * CircuitBreakerSettingsLoader.install} inside {@code
     * sharedDs.ifPresent(...)}, so a boot with no shared {@code
     * DataSource} never installed a loader at all and {@code
     * PANTERA_CIRCUIT_BREAKER_*} was never read -- {@code activeSupplier()}
     * fell straight to {@link AutoBlockSettings#defaults()}. This drives
     * the same {@code install(null, envLookup)} shape the fixed {@code
     * VertxMain} now calls unconditionally, and would have failed before
     * that fix.
     */
    @Test
    void dbLessInstallPathResolvesEnvOverDefault() {
        CircuitBreakerSettingsLoader.install(
            null, Map.of("PANTERA_CIRCUIT_BREAKER_MINIMUM_NUMBER_OF_CALLS", "42")::get
        );
        MatcherAssert.assertThat(
            CircuitBreakerSettingsLoader.activeSupplier().get().minimumNumberOfCalls(),
            new IsEqual<>(42)
        );
    }

    /**
     * Builds an {@link AuthSettingsDao} whose {@code get(key)} always
     * resolves empty, backed by dynamic-proxied JDK interfaces rather than
     * a real database -- the same {@link Proxy}-based faking technique
     * used in {@code UpstreamBreakerSettingsLoaderTest}. Doing this via the
     * actual {@link AuthSettingsDao} class (rather than a {@code null}
     * DAO) exercises the real {@code dao.get(key)} call path, which a
     * plain {@code null}-DAO test cannot.
     * @return DAO that reports every key absent, without touching a real DB
     */
    private static AuthSettingsDao emptyRowDao() {
        return new AuthSettingsDao(CircuitBreakerSettingsLoaderTest.fakeDataSource());
    }

    private static DataSource fakeDataSource() {
        return CircuitBreakerSettingsLoaderTest.proxy(DataSource.class, (target, method, args) ->
            "getConnection".equals(method.getName()) && method.getParameterCount() == 0
                ? CircuitBreakerSettingsLoaderTest.fakeConnection()
                : null
        );
    }

    private static Connection fakeConnection() {
        return CircuitBreakerSettingsLoaderTest.proxy(Connection.class, (target, method, args) ->
            "prepareStatement".equals(method.getName())
                ? CircuitBreakerSettingsLoaderTest.fakePreparedStatement()
                : null
        );
    }

    private static PreparedStatement fakePreparedStatement() {
        return CircuitBreakerSettingsLoaderTest.proxy(
            PreparedStatement.class, (target, method, args) ->
                "executeQuery".equals(method.getName())
                    ? CircuitBreakerSettingsLoaderTest.fakeEmptyResultSet()
                    : null
        );
    }

    private static ResultSet fakeEmptyResultSet() {
        return CircuitBreakerSettingsLoaderTest.proxy(ResultSet.class, (target, method, args) ->
            "next".equals(method.getName()) ? Boolean.FALSE : null
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(final Class<T> type, final InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
