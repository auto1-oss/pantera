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
package com.auto1.pantera.api.v1.download;

import com.auto1.pantera.cache.GlobalCacheConfig;
import com.auto1.pantera.db.dao.AuthSettingsDao;
import com.auto1.pantera.http.context.HandlerExecutor;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

/**
 * Production wiring for {@link DownloadTokens}: one signing key and one
 * nonce ledger per PROCESS.
 *
 * <p>The API verticle is deployed in {@code 2 × CPU} instances, each
 * constructing its own {@code ArtifactHandler}; a token minted by one
 * instance must verify on any other, so the key future and the nonce
 * store are process-wide singletons. The key is resolved on the worker
 * pool (it may read {@code auth_settings}) — never on the event loop —
 * and a failed resolution is retried on the next request instead of
 * being cached.</p>
 *
 * @since 2.2.9
 */
public final class DownloadTokenSupport {

    /**
     * Nonces must outlive the token TTL plus the accepted skew.
     */
    private static final Duration NONCE_TTL = DownloadTokens.TTL.plus(DownloadTokens.SKEW);

    private static final AtomicReference<CompletableFuture<byte[]>> KEY =
        new AtomicReference<>();

    private static final AtomicReference<NonceStore> NONCES = new AtomicReference<>();

    private DownloadTokenSupport() {
    }

    /**
     * Build the token component for a handler instance.
     *
     * @param dataSource Artifacts DB, or {@code null} in database-less mode
     * @return Token component sharing the process-wide key and ledger
     */
    public static DownloadTokens create(final DataSource dataSource) {
        final Optional<DownloadTokenKey.Store> store = Optional.ofNullable(dataSource)
            .map(AuthSettingsDao::new)
            .map(DownloadTokenSupport::adapt);
        final CompletableFuture<byte[]> key = KEY.updateAndGet(current ->
            current != null && !current.isCompletedExceptionally()
                ? current
                : CompletableFuture.supplyAsync(
                    () -> DownloadTokenKey.resolve(System.getenv(), store),
                    HandlerExecutor.get()
                )
        );
        final NonceStore nonces = NONCES.updateAndGet(current ->
            current != null ? current : GlobalCacheConfig.valkeyConnection()
                .<NonceStore>map(valkey -> new ValkeyNonceStore(valkey, NONCE_TTL))
                .orElseGet(() -> new InMemoryNonceStore(NONCE_TTL))
        );
        return new DownloadTokens(key, System::currentTimeMillis, nonces);
    }

    private static DownloadTokenKey.Store adapt(final AuthSettingsDao dao) {
        return new DownloadTokenKey.Store() {
            @Override
            public Optional<String> get(final String key) {
                return dao.get(key);
            }

            @Override
            public void putIfAbsent(final String key, final String value) {
                dao.putIfAbsent(key, value);
            }
        };
    }
}
