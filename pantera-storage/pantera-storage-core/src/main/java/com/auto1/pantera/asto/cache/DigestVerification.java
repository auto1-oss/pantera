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
package com.auto1.pantera.asto.cache;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ext.ContentDigest;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * By digest verification.
 * @since 0.25
 */
public final class DigestVerification implements CacheControl {

    /**
     * Message digest.
     */
    private final Supplier<MessageDigest> digest;

    /**
     * Expected digest.
     */
    private final byte[] expected;

    /**
     * New digest verification.
     * @param digest Message digest has func
     * @param expected Expected digest bytes
     */
    @SuppressWarnings("PMD.ArrayIsStoredDirectly")
    public DigestVerification(final Supplier<MessageDigest> digest, final byte[] expected) {
        this.digest = digest;
        this.expected = expected;
    }

    @Override
    public CompletionStage<Boolean> validate(final Key item, final Remote content) {
        return content.get().thenCompose(
            val -> val.map(pub -> new ContentDigest(pub, this.digest).bytes())
                .orElse(CompletableFuture.completedFuture(new byte[]{}))
        ).thenApply(actual -> Arrays.equals(this.expected, actual));
    }
}
