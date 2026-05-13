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
package com.auto1.pantera.rpm.pkg;

import com.auto1.pantera.http.log.EcsLogger;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.redline_rpm.ReadableChannelWrapper;
import org.redline_rpm.Scanner;
import org.redline_rpm.header.Format;
import org.redline_rpm.header.Header;

/**
 * Header of RPM package file.
 *
 * @since 0.10
 */
public final class FilePackageHeader {

    /**
     * The RPM file input stream (nullable when constructed from a Path – opened
     * lazily in {@link #header()}).
     */
    private final InputStream pckg;

    /**
     * The RPM file path (nullable when constructed from an InputStream).
     */
    private final Path path;

    /**
     * Ctor.
     *
     * @param file The RPM file.
     */
    public FilePackageHeader(final InputStream file) {
        this.pckg = file;
        this.path = null;
    }

    /**
     * Ctor.
     *
     * @param file The RPM file.
     */
    public FilePackageHeader(final Path file) {
        this.pckg = null;
        this.path = file;
    }

    /**
     * Get header.
     * Note: after the header was read from channel, for proper work of piped IO streams in
     * {@link com.auto1.pantera.asto.streams.ContentAsStream}, it's necessary fully read the channel.
     * @return The header.
     * @throws InvalidPackageException In case package is invalid.
     * @throws IOException In case of I/O error.
     */
    public Header header() throws IOException {
        if (this.path != null) {
            try (InputStream stream = Files.newInputStream(this.path)) {
                return FilePackageHeader.readHeader(stream);
            }
        }
        return FilePackageHeader.readHeader(this.pckg);
    }

    /**
     * Parse an RPM header from the given stream.
     * @param stream Input stream positioned at the start of an RPM file
     * @return Parsed header
     * @throws InvalidPackageException If the package cannot be parsed
     * @throws IOException If an I/O error occurs
     */
    private static Header readHeader(final InputStream stream) throws IOException {
        try (ReadableByteChannel chan = Channels.newChannel(stream)) {
            final Format format;
            try {
                // Use ByteArrayOutputStream for Scanner output (discarded - Scanner is just parsing)
                format = new Scanner(
                    new PrintStream(new ByteArrayOutputStream())
                ).run(new ReadableChannelWrapper(chan));
            } catch (final RuntimeException ex) {
                throw new InvalidPackageException(ex);
            }
            final Header header = format.getHeader();
            EcsLogger.debug("com.auto1.pantera.rpm")
                .message("Parsed RPM header: " + header.toString())
                .eventCategory("web")
                .eventAction("package_parsing")
                .log();
            final int bufsize = 1024;
            int read = 1;
            while (read > 0) {
                read = chan.read(ByteBuffer.allocate(bufsize));
            }
            return header;
        }
    }
}
