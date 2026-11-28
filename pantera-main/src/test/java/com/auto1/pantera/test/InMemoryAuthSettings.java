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
package com.auto1.pantera.test;

import com.auto1.pantera.db.dao.AuthSettingsDao;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * {@code auth_settings} held in a map: an {@link AuthSettingsDao} over
 * dynamic-proxied JDBC interfaces whose {@code get} reads the map and whose
 * {@code put} writes it, so the real DAO code runs without a database (the
 * faking technique of {@code UpstreamBreakerSettingsLoaderTest}, with writes).
 *
 * @since 2.2.15
 */
public final class InMemoryAuthSettings {

    /**
     * Rows by key.
     */
    private final Map<String, String> rows;

    /**
     * Whether the database is down: every connection attempt fails.
     */
    private final java.util.concurrent.atomic.AtomicBoolean down =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Settings table that starts empty.
     */
    public InMemoryAuthSettings() {
        this(Map.of());
    }

    /**
     * Settings table that starts with the given rows.
     * @param rows Initial rows
     */
    public InMemoryAuthSettings(final Map<String, String> rows) {
        this.rows = new ConcurrentHashMap<>(rows);
    }

    /**
     * The rows, live: a test may change them under the DAO.
     * @return Rows by key
     */
    public Map<String, String> rows() {
        return this.rows;
    }

    /**
     * A DAO over these rows.
     * @return DAO
     */
    public AuthSettingsDao dao() {
        return new AuthSettingsDao(
            InMemoryAuthSettings.proxy(DataSource.class, (target, method, args) -> {
                if (this.down.get()) {
                    throw new java.sql.SQLException("database down");
                }
                return "getConnection".equals(method.getName()) ? this.connection() : null;
            })
        );
    }

    /**
     * Take the database down or bring it back.
     * @param off True: every connection attempt fails from now on
     */
    public void down(final boolean off) {
        this.down.set(off);
    }

    private Connection connection() {
        return InMemoryAuthSettings.proxy(Connection.class, (target, method, args) ->
            "prepareStatement".equals(method.getName())
                ? this.statement((String) args[0]) : null
        );
    }

    private PreparedStatement statement(final String sql) {
        final Map<Integer, String> params = new HashMap<>();
        return InMemoryAuthSettings.proxy(PreparedStatement.class, (target, method, args) -> {
            final Object res;
            switch (method.getName()) {
                case "setString":
                    params.put((Integer) args[0], (String) args[1]);
                    res = null;
                    break;
                case "executeQuery":
                    res = InMemoryAuthSettings.resultSet(this.rows.get(params.get(1)));
                    break;
                case "executeUpdate":
                    if (sql.contains("DO NOTHING")) {
                        this.rows.putIfAbsent(params.get(1), params.get(2));
                    } else {
                        this.rows.put(params.get(1), params.get(2));
                    }
                    res = 1;
                    break;
                default:
                    res = null;
                    break;
            }
            return res;
        });
    }

    private static ResultSet resultSet(final String value) {
        final boolean[] read = {false};
        return InMemoryAuthSettings.proxy(ResultSet.class, (target, method, args) -> {
            final Object res;
            if ("next".equals(method.getName())) {
                res = value != null && !read[0];
                read[0] = true;
            } else if ("getString".equals(method.getName())) {
                res = value;
            } else {
                res = null;
            }
            return res;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(final Class<T> type, final InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(
            type.getClassLoader(), new Class<?>[] {type}, handler
        );
    }
}
