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
package com.auto1.pantera.asto;

/**
 * Storage extension that supports resource cleanup.
 * Implementations should properly release resources (connections, threads, etc.)
 * when close() is called.
 * 
 * <p>Usage example:
 * <pre>{@code
 * try (ManagedStorage storage = new S3Storage(...)) {
 *     storage.save(key, content).join();
 * }
 * }</pre>
 *
 * @since 1.0
 */
public interface ManagedStorage extends Storage, AutoCloseable {
    
    /**
     * Close and release all resources held by this storage.
     * After calling this method, the storage should not be used.
     * 
     * @throws Exception if an error occurs during cleanup
     */
    @Override
    void close() throws Exception;
}
