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

import com.auto1.pantera.db.dao.AuthSettingsDao;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

/**
 * {@link AuthSettingsDao} over dynamic-proxied JDBC interfaces: {@code
 * get(key)} answers from a fixed map, so the real DAO code path runs
 * without a database (the same faking technique as {@code
 * UpstreamBreakerSettingsLoaderTest}).
 *
 * @since 2.2.9
 */
final class FakeAuthSettings {

    /**
     * Utility.
     */
    private FakeAuthSettings() {
    }

    /**
     * DAO whose rows are exactly the given map.
     * @param rows Key to value
     * @return DAO
     */
    static AuthSettingsDao withRows(final Map<String, String> rows) {
        return new AuthSettingsDao(FakeAuthSettings.dataSource(rows));
    }

    /**
     * DAO that reports every key absent.
     * @return DAO
     */
    static AuthSettingsDao empty() {
        return FakeAuthSettings.withRows(Map.of());
    }

    private static DataSource dataSource(final Map<String, String> rows) {
        return FakeAuthSettings.proxy(DataSource.class, (target, method, args) ->
            "getConnection".equals(method.getName()) && method.getParameterCount() == 0
                ? FakeAuthSettings.connection(rows) : null
        );
    }

    private static Connection connection(final Map<String, String> rows) {
        return FakeAuthSettings.proxy(Connection.class, (target, method, args) ->
            "prepareStatement".equals(method.getName())
                ? FakeAuthSettings.statement(rows) : null
        );
    }

    private static PreparedStatement statement(final Map<String, String> rows) {
        final AtomicReference<String> key = new AtomicReference<>();
        return FakeAuthSettings.proxy(PreparedStatement.class, (target, method, args) -> {
            if ("setString".equals(method.getName())) {
                key.set((String) args[1]);
                return null;
            }
            if ("executeQuery".equals(method.getName())) {
                return FakeAuthSettings.resultSet(rows.get(key.get()));
            }
            return null;
        });
    }

    private static ResultSet resultSet(final String value) {
        return FakeAuthSettings.proxy(ResultSet.class, (target, method, args) -> {
            if ("next".equals(method.getName())) {
                return value != null;
            }
            if ("getString".equals(method.getName())) {
                return value;
            }
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(final Class<T> type, final InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
