/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.rpm.pkg;

import com.auto1.pantera.asto.misc.UncheckedIOScalar;
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
     * The RPM file input stream.
     */
    private final InputStream pckg;

    /**
     * Ctor.
     *
     * @param file The RPM file.
     */
    public FilePackageHeader(final InputStream file) {
        this.pckg = file;
    }

    /**
     * Ctor.
     *
     * @param file The RPM file.
     */
    public FilePackageHeader(final Path file) {
        this(new UncheckedIOScalar<>(() -> Files.newInputStream(file)).value());
    }

    /**
     * Get header.
     * Note: after the header was read from channel, for proper work of piped IO streams in
     * {@link com.auto1.pantera.asto.streams.ContentAsStream}, it's necessary fully read the channel.
     * @return The header.
     * @throws InvalidPackageException In case package is invalid.
     * @throws IOException In case of I/O error.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public Header header() throws IOException {
        try (ReadableByteChannel chan = Channels.newChannel(this.pckg)) {
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
                .eventCategory("repository")
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
