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
package com.auto1.pantera.rpm.files;

import com.auto1.pantera.http.log.EcsLogger;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.utils.IOUtils;

/**
 * Gzip.
 * @since 0.8
 */
public final class Gzip {

    /**
     * Path to gzip.
     */
    private final Path file;

    /**
     * Ctor.
     * @param file Path
     */
    public Gzip(final Path file) {
        this.file = file;
    }

    /**
     * Unpacks tar gzip to the temp dir.
     * @param dest Destination directory
     * @throws IOException If fails
     */
    public void unpackTar(final Path dest) throws IOException {
        try (
            GzipCompressorInputStream input =
                new GzipCompressorInputStream(Files.newInputStream(this.file));
            TarArchiveInputStream tar = new TarArchiveInputStream(input)
        ) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                final Path next = dest.resolve(entry.getName());
                if (!next.normalize().startsWith(dest)) {
                    throw new IllegalStateException("Bad tar.gz entry");
                }
                if (entry.isDirectory()) {
                    next.toFile().mkdirs();
                } else {
                    try (OutputStream out = Files.newOutputStream(next)) {
                        IOUtils.copy(tar, out);
                    }
                }
            }
        }
        EcsLogger.debug("com.auto1.pantera.rpm")
            .message("Unpacked tar.gz")
            .eventCategory("web")
            .eventAction("archive_extraction")
            .field("file.path", this.file.toString())
            .field("destination.address", dest.toString())
            .log();
    }

    /**
     * Unpacks gzip to the temp dir.
     * @param dest Destination directory
     * @throws IOException If fails
     */
    public void unpack(final Path dest) throws IOException {
        try (OutputStream out = Files.newOutputStream(dest);
            GZIPInputStream input = new GZIPInputStream(Files.newInputStream(this.file))) {
            IOUtils.copy(input, out);
        }
        EcsLogger.debug("com.auto1.pantera.rpm")
            .message("Unpacked gz")
            .eventCategory("web")
            .eventAction("archive_extraction")
            .field("file.path", this.file.toString())
            .field("destination.address", dest.toString())
            .log();
    }
}
