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
package com.auto1.pantera.npm.misc;

import com.auto1.pantera.asto.Content;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;

/**
 * Collects the output of a {@link PackumentTransform} that is small by
 * construction (a skeleton, one manifest) into bytes.
 *
 * @since 2.2.11
 */
final class SmallJson {

    /**
     * Transform output.
     */
    private final Content content;

    /**
     * Ctor.
     *
     * @param content Transform output
     */
    SmallJson(final Content content) {
        this.content = content;
    }

    /**
     * Collect.
     *
     * @return Bytes
     */
    CompletableFuture<byte[]> bytes() {
        return Flowable.fromPublisher(this.content)
            .reduce(
                new ByteArrayOutputStream(),
                (out, buf) -> {
                    final byte[] chunk = new byte[buf.remaining()];
                    buf.get(chunk);
                    out.write(chunk, 0, chunk.length);
                    return out;
                }
            )
            .map(ByteArrayOutputStream::toByteArray)
            .to(SingleInterop.get())
            .toCompletableFuture();
    }
}
