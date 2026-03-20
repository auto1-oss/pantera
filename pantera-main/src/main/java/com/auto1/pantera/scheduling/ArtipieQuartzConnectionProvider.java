/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.scheduling;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.quartz.utils.ConnectionProvider;

/**
 * Quartz {@link ConnectionProvider} backed by an existing {@link DataSource}.
 * <p>
 * Allows Quartz JDBC job store to reuse the same connection pool (HikariCP)
 * that Artipie uses for its artifacts database, eliminating the need for
 * Quartz to manage its own connection pool.
 * <p>
 * This provider is registered programmatically via
 * {@link org.quartz.utils.DBConnectionManager#addConnectionProvider(String, ConnectionProvider)}
 * before the Quartz scheduler is created.
 *
 * @since 1.20.13
 */
public final class ArtipieQuartzConnectionProvider implements ConnectionProvider {

    /**
     * The data source name used in Quartz configuration properties.
     * Must match the value of {@code org.quartz.jobStore.dataSource}.
     */
    public static final String DS_NAME = "artipieDS";

    /**
     * Underlying data source (typically HikariCP).
     */
    private final DataSource dataSource;

    /**
     * Ctor.
     * @param dataSource Existing data source to delegate to
     */
    public ArtipieQuartzConnectionProvider(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return this.dataSource.getConnection();
    }

    @Override
    public void shutdown() throws SQLException {
        // HikariCP manages its own lifecycle; nothing to do here.
    }

    @Override
    public void initialize() throws SQLException {
        // Already initialized via Artipie's HikariCP setup.
    }
}
