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
package com.auto1.pantera.asto.blob;

/**
 * Process-wide static holder for the REAL {@link StorageInvalidationBus}
 * implementation (built over {@code pantera-core}'s {@code
 * CacheInvalidationPubSub} when clustering/Valkey is configured), mirroring
 * the {@code UpstreamBreakerSettingsLoader}/{@code CircuitBreakerSettingsLoader}
 * static-install pattern this codebase already uses for optional
 * cross-module wiring that a lower module's SPI signature cannot carry
 * directly.
 *
 * <p><strong>Why a registry instead of a constructor/method argument:</strong>
 * {@code StorageFactory#newStorage(Config)} -- the SPI {@code
 * S3StorageFactory} (in {@code pantera-storage-s3}, which cannot depend on
 * {@code pantera-core} either) implements -- takes only a {@code Config}.
 * There is no channel in that SPI to hand a {@code pantera-core}-built bus
 * down to the factory. Instead, {@code pantera-main}'s boot sequence calls
 * {@link #install(StorageInvalidationBus)} once wherever it constructs its
 * real, Valkey-backed bus (mirroring exactly where it already constructs
 * {@code CacheInvalidationPubSub} for the existing auth/filters/policy
 * channels), and any {@code CachedBlobStorage}-constructing factory reads
 * {@link #active()} instead of receiving the bus as an argument.</p>
 *
 * <p>A DB-less or clustering-less boot never calls {@link #install}, and
 * {@link #active()} falls back to {@link StorageInvalidationBus#NOOP} --
 * every {@code CachedBlobStorage} behaves exactly as it did before WS1.5
 * until an operator opts into clustering.</p>
 *
 * @since 2.3.0
 */
public final class StorageInvalidationBusRegistry {

    /**
     * Installed bus, or {@code null} if none (single-instance / DB-less boot).
     */
    private static volatile StorageInvalidationBus installed;

    private StorageInvalidationBusRegistry() {
    }

    /**
     * Install the process-wide bus. Idempotent -- a later call replaces an
     * earlier one.
     *
     * @param bus Real bus implementation.
     */
    public static void install(final StorageInvalidationBus bus) {
        StorageInvalidationBusRegistry.installed = bus;
    }

    /**
     * Clear the installed bus (tests, shutdown).
     */
    public static void uninstall() {
        StorageInvalidationBusRegistry.installed = null;
    }

    /**
     * The active bus: the installed one, or {@link StorageInvalidationBus#NOOP}
     * if none was installed. Safe to call before {@link #install}.
     *
     * @return Active bus, never {@code null}.
     */
    public static StorageInvalidationBus active() {
        final StorageInvalidationBus current = StorageInvalidationBusRegistry.installed;
        return current == null ? StorageInvalidationBus.NOOP : current;
    }
}
