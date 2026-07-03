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
package com.auto1.pantera.hex.tarball;

import com.auto1.pantera.PanteraException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Allows to read content of named-entries from tar-source.
 *
 * @since 0.1
 */
public class TarReader {
    /**
     * File metadata.config.
     */
    public static final String CHECKSUM = "CHECKSUM";

    /**
     * File metadata.config.
     */
    public static final String METADATA = "metadata.config";

    /**
     * Portion size to read archive.
     */
    private static final int SIZE = 1024;

    /**
     * Tar archive as bytes.
     */
    private final byte[] bytes;

    /**
     * Ctor.
     * @param bytes Tar archive as bytes
     */
    public TarReader(final byte[] bytes) {
        this.bytes = Arrays.copyOf(bytes, bytes.length);
    }

    /**
     * Reads content of entry stored in tar-archive.
     * @param name Name of entry
     * @return Optional of tar entry in byte array
     */
    public Optional<byte[]> readEntryContent(final String name) {
        byte[] content = null;
        try {
            try (ByteArrayInputStream bis = new ByteArrayInputStream(this.bytes);
                TarArchiveInputStream tar = new TarArchiveInputStream(bis)
            ) {
                TarArchiveEntry entry;
                while ((entry = tar.getNextEntry()) != null) {
                    if (name.equals(entry.getName())) {
                        final ByteArrayOutputStream entrycontent = new ByteArrayOutputStream();
                        int len;
                        final byte[] buf = new byte[TarReader.SIZE];
                        while ((len = tar.read(buf)) != -1) {
                            entrycontent.write(buf, 0, len);
                        }
                        content = entrycontent.toByteArray();
                        break;
                    }
                }
            }
        } catch (final IOException ioex) {
            throw new PanteraException(
                String.format("Cannot read content of '%s' from tar-archive", name),
                ioex
            );
        }
        return Optional.ofNullable(content);
    }
}
