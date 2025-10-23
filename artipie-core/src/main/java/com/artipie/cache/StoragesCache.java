/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import com.amihaiemil.eoyaml.YamlMapping;
import com.artipie.ArtipieException;
import com.artipie.asto.Storage;
import com.artipie.asto.factory.Config;
import com.artipie.asto.factory.StoragesLoader;
import com.artipie.asto.misc.Cleanable;
import com.artipie.jfr.JfrStorage;
import com.artipie.jfr.StorageCreateEvent;
import com.artipie.misc.ArtipieProperties;
import com.artipie.misc.Property;
import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import org.apache.commons.lang3.NotImplementedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of cache for storages with similar configurations
 * in Artipie settings using {@link LoadingCache}.
 * Properly closes Storage instances when evicted from cache to prevent resource leaks.
 */
public class StoragesCache implements Cleanable<YamlMapping> {

    private static final Logger LOGGER = LoggerFactory.getLogger(StoragesCache.class);

    /**
     * Cache for storages.
     */
    private final Cache<YamlMapping, Storage> cache;

    /**
     * Ctor.
     */
    public StoragesCache() {
        this.cache = CacheBuilder.newBuilder()
            .expireAfterWrite(
                new Property(ArtipieProperties.STORAGE_TIMEOUT).asLongOrDefault(180_000L),
                TimeUnit.MILLISECONDS
            )
            .softValues()
            .removalListener(new StorageCleanupListener())
            .build();
    }

    /**
     * Finds storage by specified in settings configuration cache or creates
     * a new item and caches it.
     *
     * @param yaml Storage settings
     * @return Storage
     */
    public Storage storage(final YamlMapping yaml) {
        final String type = yaml.string("type");
        if (Strings.isNullOrEmpty(type)) {
            throw new ArtipieException("Storage type cannot be null or empty.");
        }
        try {
            return this.cache.get(
                yaml,
                () -> {
                    final Storage res;
                    final StorageCreateEvent event = new StorageCreateEvent();
                    if (event.isEnabled()) {
                        event.begin();
                        res = new JfrStorage(
                            StoragesLoader.STORAGES
                                .newObject(type, new Config.YamlStorageConfig(yaml))
                        );
                        event.storage = res.identifier();
                        event.commit();
                    } else {
                        res = new JfrStorage(
                            StoragesLoader.STORAGES
                                .newObject(type, new Config.YamlStorageConfig(yaml))
                        );
                    }
                    return res;
                }
            );
        } catch (final ExecutionException err) {
            throw new ArtipieException(err);
        }
    }

    /**
     * Returns the approximate number of entries in this cache.
     *
     * @return Number of entries
     */
    public long size() {
        return this.cache.size();
    }

    @Override
    public String toString() {
        return String.format(
            "%s(size=%d)",
            this.getClass().getSimpleName(), this.cache.size()
        );
    }

    @Override
    public void invalidate(final YamlMapping mapping) {
        throw new NotImplementedException("This method is not supported in cached storages!");
    }

    @Override
    public void invalidateAll() {
        this.cache.invalidateAll();
    }

    /**
     * Removal listener that properly closes Storage instances to prevent resource leaks.
     * This is critical for S3Storage which holds S3AsyncClient with connection pools and threads.
     */
    private static class StorageCleanupListener implements RemovalListener<YamlMapping, Storage> {
        @Override
        public void onRemoval(final RemovalNotification<YamlMapping, Storage> notification) {
            final Storage storage = notification.getValue();
            if (storage == null) {
                return;
            }
            
            try {
                // Unwrap JfrStorage using reflection to access the wrapped storage
                Storage actual = storage;
                if (storage instanceof JfrStorage) {
                    try {
                        final java.lang.reflect.Field field = JfrStorage.class.getDeclaredField("original");
                        field.setAccessible(true);
                        actual = (Storage) field.get(storage);
                    } catch (final Exception ex) {
                        LOGGER.warn("Failed to unwrap JfrStorage, will try to close wrapper", ex);
                        actual = storage;
                    }
                }
                
                // Close if AutoCloseable (e.g., DiskCacheStorage, S3Storage via ManagedStorage)
                if (actual instanceof AutoCloseable) {
                    ((AutoCloseable) actual).close();
                    LOGGER.debug("Closed storage: {} (reason: {})", 
                        actual.identifier(), notification.getCause());
                } else {
                    LOGGER.debug("Storage {} is not AutoCloseable, skipping cleanup", 
                        actual.identifier());
                }
            } catch (final Exception ex) {
                LOGGER.error("Failed to close storage: {}", storage.identifier(), ex);
            }
        }
    }
}
