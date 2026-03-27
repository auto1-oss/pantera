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
package com.auto1.pantera.asto.test;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Splitting;
import com.auto1.pantera.asto.Storage;
import io.reactivex.Flowable;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Storage for tests.
 * <p/>
 * Reading a value by a key return content that emit chunks of bytes
 * with random size and random delays.
 *
 * @since 1.12
 */
public class ReadWithDelaysStorage extends Storage.Wrap {
    /**
     * Ctor.
     *
     * @param delegate Original storage.
     */
    public ReadWithDelaysStorage(final Storage delegate) {
        super(delegate);
    }

    @Override
    public final CompletableFuture<Content> value(final Key key) {
        final Random random = new Random();
        return super.value(key)
            .thenApply(
                content -> new Content.From(
                    Flowable.fromPublisher(content)
                        .flatMap(
                            buffer -> new Splitting(
                                buffer,
                                (random.nextInt(9) + 1) * 1024
                            ).publisher()
                        )
                        .delay(random.nextInt(5_000), TimeUnit.MILLISECONDS)
                )
            );
    }
}
