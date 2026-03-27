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
package com.auto1.pantera.composer.test;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Empty ZIP archive for using in tests.
 * @since 0.4
 */
public final class EmptyZip {
    /**
     * Entry name. As archive should contains whatever.
     */
    private final String entry;

    /**
     * Ctor.
     */
    public EmptyZip() {
        this("whatever");
    }

    /**
     * Ctor.
     * @param entry Entry name
     */
    public EmptyZip(final String entry) {
        this.entry = entry;
    }

    /**
     * Obtains ZIP archive.
     * @return ZIP archive
     * @throws Exception In case of error during creating ZIP archive
     */
    public byte[] value() throws Exception {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final ZipOutputStream zos = new ZipOutputStream(bos);
        zos.putNextEntry(new ZipEntry(this.entry));
        zos.close();
        return bos.toByteArray();
    }
}
