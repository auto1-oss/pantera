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
package com.auto1.pantera.rpm.asto;

import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.streams.StorageValuePipeline;
import java.io.IOException;
import java.util.concurrent.CompletionStage;
import java.util.zip.GZIPOutputStream;

/**
 * Archive storage item.
 * @since 1.9
 */
final class AstoArchive {

    /**
     * Asto storage.
     */
    private final Storage asto;

    /**
     * Ctor.
     * @param asto Asto storage
     */
    AstoArchive(final Storage asto) {
        this.asto = asto;
    }

    /**
     * Compress storage item with gzip compression.
     * @param key Item to gzip
     * @return Completable action
     */
    public CompletionStage<Void> gzip(final Key key) {
        return new StorageValuePipeline<>(this.asto, key).process(
            (inpt, out) -> {
                try (GZIPOutputStream gzos = new GZIPOutputStream(out)) {
                    final byte[] buffer = new byte[1024 * 8];
                    while (true) {
                        final int length = inpt.get().read(buffer);
                        if (length < 0) {
                            break;
                        }
                        gzos.write(buffer, 0, length);
                    }
                    gzos.finish();
                } catch (final IOException err) {
                    throw new PanteraIOException(err);
                }
            }
        );
    }
}
