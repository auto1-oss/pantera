/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.rpm.asto;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.rpm.meta.XmlPackage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import org.apache.commons.io.IOUtils;

/**
 * Reads and unpacks metadata.
 *
 * @since 1.9.4
 */
public final class MetadataBytes {

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Ctor.
     * @param storage Storage
     */
    public MetadataBytes(final Storage storage) {
        this.storage = storage;
    }

    /**
     * Reads and unpacks data in bytes.
     * @param key Key
     * @return Bytes
     * @throws IOException If fails
     */
    public byte[] value(final Key key) throws IOException {
        return IOUtils.toByteArray(
            new GZIPInputStream(
                new ByteArrayInputStream(new BlockingStorage(this.storage).value(key))
            )
        );
    }

    /**
     * Reads and unpacks data in bytes.
     * @param type Type of metadata
     * @return Bytes
     * @throws IOException If fails
     */
    public byte[] value(final XmlPackage type) throws IOException {
        return this.value(this.findKey(type));
    }

    /**
     * Reads and unpacks data in bytes.
     * @param base Base path
     * @param type Type of metadata
     * @return Bytes
     * @throws IOException If fails
     */
    public byte[] value(final Key base, final XmlPackage type) throws IOException {
        return this.value(new Key.From(base, type.name()));
    }

    /**
     * Finds key.
     * @param type Type of metadata
     * @return Key
     */
    private Key findKey(final XmlPackage type) {
        return new BlockingStorage(this.storage)
            .list(new Key.From("repodata")).stream()
            .filter(
                item -> item.string().contains(type.lowercase())
            )
            .findFirst()
            .get();
    }
}
