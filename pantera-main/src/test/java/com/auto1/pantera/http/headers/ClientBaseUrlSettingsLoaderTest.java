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
package com.auto1.pantera.http.headers;

import com.auto1.pantera.db.dao.AuthSettingsDao;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Tests for {@link ClientBaseUrlSettingsLoader}: DB-less fallback to
 * hardcoded defaults, the uninstalled {@code activeSupplier} path, and that
 * {@code install}/{@code uninstall} keep {@link ClientBaseUrlSettingsRegistry}
 * — the {@code pantera-core}-side holder {@link ClientBaseUrl} actually
 * reads from — in sync. DB-backed resolution shares the exact resolve chain
 * with the long-standing breaker loaders and is covered by the admin-endpoint
 * round-trip.
 *
 * @since 2.3.0
 */
final class ClientBaseUrlSettingsLoaderTest {

    @AfterEach
    void tearDown() {
        ClientBaseUrlSettingsLoader.uninstall();
    }

    @Test
    void activeSupplierWithoutInstallReturnsDefaults() {
        MatcherAssert.assertThat(
            ClientBaseUrlSettingsLoader.activeSupplier().get(),
            new IsEqual<>(ClientBaseUrlSettings.defaults())
        );
    }

    @Test
    void nullDaoLoaderFallsBackToDefaults() {
        final ClientBaseUrlSettingsLoader loader = new ClientBaseUrlSettingsLoader(null);
        MatcherAssert.assertThat(loader.get(), new IsEqual<>(ClientBaseUrlSettings.defaults()));
    }

    /**
     * Proves the hot-reload wiring end to end at the loader level: {@code
     * install} feeds {@link ClientBaseUrlSettingsRegistry}, and {@code
     * invalidate} — the call the admin PUT endpoint and the cross-node
     * broadcast subscriber both make — is what {@link ClientBaseUrl}
     * ultimately benefits from without any restart.
     */
    @Test
    void installFeedsTheRegistryAndInvalidateIsSafeWithoutADb() {
        ClientBaseUrlSettingsLoader.install(null);
        MatcherAssert.assertThat(
            "install() must also install into the core-side registry",
            ClientBaseUrlSettingsRegistry.active(), new IsEqual<>(ClientBaseUrlSettings.defaults())
        );
        final ClientBaseUrlSettingsLoader loader = ClientBaseUrlSettingsLoader.installed();
        loader.get();
        loader.invalidate();
        MatcherAssert.assertThat(
            "invalidate reloads without error and still resolves",
            ClientBaseUrlSettingsLoader.activeSupplier().get().hostAllowlist(),
            new IsEqual<>(List.of())
        );
    }

    @Test
    void uninstallClearsBothTheLoaderAndTheRegistry() {
        ClientBaseUrlSettingsLoader.install(null);
        ClientBaseUrlSettingsLoader.uninstall();
        MatcherAssert.assertThat(
            "uninstall() must clear the installed() accessor",
            ClientBaseUrlSettingsLoader.installed(), new IsNull<>()
        );
        MatcherAssert.assertThat(
            "uninstall() must also clear the core-side registry",
            ClientBaseUrlSettingsRegistry.active(), new IsEqual<>(ClientBaseUrlSettings.defaults())
        );
    }

    /**
     * Regression guard for the V137 seed-shadowing bug: a DB-backed
     * deployment where the migration correctly leaves both keys unseeded
     * has an installed DAO whose {@code get(key)} genuinely returns empty
     * (not a {@code null} DAO -- that DB-less case is
     * {@link #dbLessInstallPathResolvesEnvOverDefault()} below). Before the
     * fix, {@code V137__client_base_url_settings.sql} unconditionally wrote
     * {@code trust_forwarded_headers='false'}, so {@code dao.get(key)}
     * always returned a present row and the env tier below was never
     * consulted -- this test would have failed against that migration's
     * behaviour reproduced at the loader level.
     */
    @Test
    void daoReportsKeyAbsentEnvTierWinsOverDefault() {
        final ClientBaseUrlSettingsLoader loader = new ClientBaseUrlSettingsLoader(
            ClientBaseUrlSettingsLoaderTest.emptyRowDao(),
            Map.of("PANTERA_TRUST_FORWARDED_HEADERS", "true")::get
        );
        MatcherAssert.assertThat(loader.get().trustForwardedHeaders(), new IsEqual<>(true));
    }

    /**
     * Regression guard for the DB-less-boot bug: before the fix, {@code
     * VertxMain} only called {@code ClientBaseUrlSettingsLoader.install}
     * inside {@code sharedDs.ifPresent(...)}, so a boot with no shared
     * {@code DataSource} never installed a loader at all and {@code
     * PANTERA_TRUST_FORWARDED_HEADERS} was never read -- {@code
     * activeSupplier()} fell straight to {@link
     * ClientBaseUrlSettings#defaults()}. This drives the same {@code
     * install(null, envLookup)} shape the fixed {@code VertxMain} now calls
     * unconditionally, and would have failed before that fix.
     */
    @Test
    void dbLessInstallPathResolvesEnvOverDefault() {
        ClientBaseUrlSettingsLoader.install(
            null, Map.of("PANTERA_TRUST_FORWARDED_HEADERS", "true")::get
        );
        MatcherAssert.assertThat(
            ClientBaseUrlSettingsLoader.activeSupplier().get().trustForwardedHeaders(),
            new IsEqual<>(true)
        );
    }

    /**
     * Same shape as {@link #daoReportsKeyAbsentEnvTierWinsOverDefault()}, but
     * for the canonical base URL (fixwave-h, 2.3.0): a DB-backed deployment
     * where {@code client_base_url} genuinely has no row must still resolve
     * {@code PANTERA_CLIENT_BASE_URL} rather than falling straight to the
     * hardcoded default.
     */
    @Test
    void canonicalBaseUrlEnvTierWinsOverDefaultWhenDaoReportsKeyAbsent() {
        final ClientBaseUrlSettingsLoader loader = new ClientBaseUrlSettingsLoader(
            ClientBaseUrlSettingsLoaderTest.emptyRowDao(),
            Map.of("PANTERA_CLIENT_BASE_URL", "http://localhost:9999")::get
        );
        MatcherAssert.assertThat(
            loader.get().canonicalBaseUrl(), new IsEqual<>("http://localhost:9999")
        );
    }

    /**
     * DB-less boot resolves the canonical base URL from its env var too --
     * the same regression class {@link #dbLessInstallPathResolvesEnvOverDefault()}
     * guards for {@code trust_forwarded_headers}.
     */
    @Test
    void canonicalBaseUrlDbLessInstallPathResolvesEnvOverDefault() {
        ClientBaseUrlSettingsLoader.install(
            null, Map.of("PANTERA_CLIENT_BASE_URL", "https://reg.example.com/artifactory")::get
        );
        MatcherAssert.assertThat(
            ClientBaseUrlSettingsLoader.activeSupplier().get().canonicalBaseUrl(),
            new IsEqual<>("https://reg.example.com/artifactory")
        );
    }

    /**
     * No DB row and no env var: the canonical base URL resolves to the
     * hardcoded default (unset), exactly like a fresh install.
     */
    @Test
    void canonicalBaseUrlDefaultsToUnsetWithNoDbRowAndNoEnvVar() {
        final ClientBaseUrlSettingsLoader loader = new ClientBaseUrlSettingsLoader(
            ClientBaseUrlSettingsLoaderTest.emptyRowDao(), Map.<String, String>of()::get
        );
        MatcherAssert.assertThat(loader.get().canonicalBaseUrl(), new IsEqual<>(""));
    }

    /**
     * Builds an {@link AuthSettingsDao} whose {@code get(key)} always
     * resolves empty, backed by dynamic-proxied JDK interfaces rather than
     * a real database -- the same {@link Proxy}-based faking technique
     * already used in {@code GroupMetadataCacheStaleFallbackTest} for a
     * Redis client. Doing this via the actual {@link AuthSettingsDao}
     * class (rather than a {@code null} DAO) exercises the real {@code
     * dao.get(key)} call path, which a plain {@code null}-DAO test cannot.
     * @return DAO that reports every key absent, without touching a real DB
     */
    private static AuthSettingsDao emptyRowDao() {
        return new AuthSettingsDao(ClientBaseUrlSettingsLoaderTest.fakeDataSource());
    }

    private static DataSource fakeDataSource() {
        return ClientBaseUrlSettingsLoaderTest.proxy(DataSource.class, (target, method, args) ->
            "getConnection".equals(method.getName()) && method.getParameterCount() == 0
                ? ClientBaseUrlSettingsLoaderTest.fakeConnection()
                : null
        );
    }

    private static Connection fakeConnection() {
        return ClientBaseUrlSettingsLoaderTest.proxy(Connection.class, (target, method, args) ->
            "prepareStatement".equals(method.getName())
                ? ClientBaseUrlSettingsLoaderTest.fakePreparedStatement()
                : null
        );
    }

    private static PreparedStatement fakePreparedStatement() {
        return ClientBaseUrlSettingsLoaderTest.proxy(
            PreparedStatement.class, (target, method, args) ->
                "executeQuery".equals(method.getName())
                    ? ClientBaseUrlSettingsLoaderTest.fakeEmptyResultSet()
                    : null
        );
    }

    private static ResultSet fakeEmptyResultSet() {
        return ClientBaseUrlSettingsLoaderTest.proxy(ResultSet.class, (target, method, args) ->
            "next".equals(method.getName()) ? Boolean.FALSE : null
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(final Class<T> type, final InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
