/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto.factory;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.asto.Storage;

/**
 * Storage factory interface.
 * Factories that create closeable storages (e.g., S3Storage) should ensure
 * proper resource cleanup by implementing closeStorage() method.
 *
 * @since 1.13.0
 */
public interface StorageFactory {

    /**
     * Create new storage.
     *
     * @param cfg Storage configuration.
     * @return Storage instance
     */
    Storage newStorage(Config cfg);

    /**
     * Close and cleanup storage resources.
     * Default implementation attempts to close storage if it implements AutoCloseable.
     * Factories should override this if custom cleanup is needed.
     * 
     * <p>This method enables proper resource management for ManagedStorage instances
     * even when they're returned as Storage interface:
     * <pre>{@code
     * Storage storage = factory.newStorage(config);
     * try {
     *     storage.save(key, content).join();
     * } finally {
     *     factory.closeStorage(storage);
     * }
     * }</pre>
     * 
     * @param storage Storage instance to close (may be null)
     * @since 1.0
     */
    default void closeStorage(final Storage storage) {
        if (storage instanceof AutoCloseable) {
            try {
                ((AutoCloseable) storage).close();
            } catch (final Exception e) {
                // Log but don't throw - best effort cleanup
                System.err.println("Failed to close storage: " + e.getMessage());
            }
        }
    }

    /**
     * Create new storage from YAML configuration.
     *
     * @param cfg Storage configuration.
     * @return Storage instance
     */
    default Storage newStorage(final YamlMapping cfg) {
        return this.newStorage(
            new Config.YamlStorageConfig(cfg)
        );
    }
}
