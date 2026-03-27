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
package com.auto1.pantera.db;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL test configuration for cross-platform compatibility.
 * @since 1.0
 */
public final class PostgreSQLTestConfig {

    /**
     * PostgreSQL Docker image that supports both ARM64 and AMD64.
     */
    private static final String POSTGRES_IMAGE = "postgres:15-alpine";

    /**
     * Database name for tests.
     */
    private static final String DATABASE_NAME = "artifacts";

    /**
     * Username for tests.
     */
    private static final String USERNAME = "pantera";

    /**
     * Password for tests.
     */
    private static final String PASSWORD = "pantera";

    /**
     * Private constructor to prevent instantiation.
     */
    private PostgreSQLTestConfig() {
        // Utility class
    }

    /**
     * Creates a PostgreSQL container configured for cross-platform testing.
     * @return Configured PostgreSQL container
     */
    public static PostgreSQLContainer<?> createContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
            .withDatabaseName(DATABASE_NAME)
            .withUsername(USERNAME)
            .withPassword(PASSWORD)
            .withReuse(true)
            .withLabel("test-container", "pantera-postgres");
    }

    /**
     * Gets the database name.
     * @return Database name
     */
    public static String getDatabaseName() {
        return DATABASE_NAME;
    }

    /**
     * Gets the username.
     * @return Username
     */
    public static String getUsername() {
        return USERNAME;
    }

    /**
     * Gets the password.
     * @return Password
     */
    public static String getPassword() {
        return PASSWORD;
    }
}
