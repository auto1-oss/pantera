/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.db;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Singleton PostgreSQL container shared across all tests.
 * Uses Testcontainers recommended singleton pattern.
 * 
 * @since 1.0
 */
public final class SharedPostgreSQLContainer {

    private static final PostgreSQLContainer<?> INSTANCE;

    static {
        try {
            System.out.println("[SharedPostgreSQLContainer] Initializing singleton...");
            INSTANCE = PostgreSQLTestConfig.createContainer();

            // Ensure container is started before accessing ports
            if (!INSTANCE.isRunning()) {
                System.out.println("[SharedPostgreSQLContainer] Starting container...");
                INSTANCE.start();
            } else {
                System.out.println("[SharedPostgreSQLContainer] Container already running (reused)");
            }

            // Wait for container to be fully ready
            System.out.println("[SharedPostgreSQLContainer] Waiting for container to be ready...");
            Thread.sleep(2000); // Give container time to initialize port mappings

            // Verify container is truly ready by testing connection
            System.out.println("[SharedPostgreSQLContainer] Container started, verifying connectivity...");
            int maxRetries = 10;
            for (int i = 0; i < maxRetries; i++) {
                try {
                    java.sql.DriverManager.getConnection(
                            INSTANCE.getJdbcUrl(),
                            INSTANCE.getUsername(),
                            INSTANCE.getPassword()).close();
                    System.out.println("[SharedPostgreSQLContainer] Connection verified!");
                    break;
                } catch (Exception e) {
                    if (i == maxRetries - 1)
                        throw e;
                    System.out.println("[SharedPostgreSQLContainer] Waiting for connectivity... (" + (i + 1) + "/"
                            + maxRetries + ")");
                    Thread.sleep(1000);
                }
            }

            System.out.println(
                    "[SharedPostgreSQLContainer] Ready at " + INSTANCE.getHost() + ":" + INSTANCE.getFirstMappedPort());
            System.out.println("[SharedPostgreSQLContainer] JDBC URL: " + INSTANCE.getJdbcUrl());

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("[SharedPostgreSQLContainer] Stopping...");
                INSTANCE.stop();
            }));
        } catch (Exception e) {
            System.err.println("[SharedPostgreSQLContainer] FAILED TO START: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to start shared PostgreSQL container", e);
        }
    }

    private SharedPostgreSQLContainer() {
        // Utility class
    }

    /**
     * Get the singleton PostgreSQL container instance.
     * 
     * @return PostgreSQL container
     */
    public static PostgreSQLContainer<?> getInstance() {
        return INSTANCE;
    }
}
