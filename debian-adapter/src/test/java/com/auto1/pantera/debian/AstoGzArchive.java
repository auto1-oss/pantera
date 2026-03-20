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
package com.auto1.pantera.debian;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import java.nio.charset.StandardCharsets;

/**
 * GzArchive: packs or unpacks.
 * @since 0.6
 */
public final class AstoGzArchive {

    /**
     * Abstract storage.
     */
    private final Storage asto;

    /**
     * Ctor.
     * @param asto Abstract storage
     */
    public AstoGzArchive(final Storage asto) {
        this.asto = asto;
    }

    /**
     * Compress provided bytes in gz format and adds item to storage by provided key.
     * @param bytes Bytes to pack
     * @param key Storage key
     */
    public void packAndSave(final byte[] bytes, final Key key) {
        this.asto.save(key, new Content.From(new GzArchive().compress(bytes))).join();
    }

    /**
     * Compress provided string in gz format and adds item to storage by provided key.
     * @param content String to pack
     * @param key Storage key
     */
    public void packAndSave(final String content, final Key key) {
        this.packAndSave(content.getBytes(StandardCharsets.UTF_8), key);
    }

    /**
     * Unpacks storage item and returns unpacked content as string.
     * @param key Storage item
     * @return Unpacked string
     */
    public String unpack(final Key key) {
        return new GzArchive().decompress(new BlockingStorage(this.asto).value(key));
    }
}
