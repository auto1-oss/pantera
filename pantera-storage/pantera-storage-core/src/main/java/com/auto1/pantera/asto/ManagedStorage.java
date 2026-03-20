/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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
