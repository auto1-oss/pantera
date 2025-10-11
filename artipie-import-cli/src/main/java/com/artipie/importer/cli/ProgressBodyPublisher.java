/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer.cli;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Body publisher that reports upload progress to {@link ConsoleProgress}.
 */
final class ProgressBodyPublisher {

    private ProgressBodyPublisher() {
    }

    static BodyPublisher ofFile(final Path file, final ConsoleProgress progress) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(progress, "progress");
        // Provide a fresh input stream per subscription (retry-safe)
        return BodyPublishers.ofInputStream(() -> {
            try {
                return new CountingInputStream(Files.newInputStream(file), progress);
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
        });
    }

    private static final class CountingInputStream extends FilterInputStream {
        private final ConsoleProgress progress;

        private CountingInputStream(final InputStream in, final ConsoleProgress progress) {
            super(in);
            this.progress = progress;
        }

        @Override
        public int read() throws IOException {
            final int b = super.read();
            if (b >= 0) {
                this.progress.addUploaded(1);
            }
            return b;
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            final int n = super.read(b, off, len);
            if (n > 0) {
                this.progress.addUploaded(n);
            }
            return n;
        }

        @Override
        public long skip(final long n) throws IOException {
            final long skipped = super.skip(n);
            if (skipped > 0) {
                this.progress.addUploaded(skipped);
            }
            return skipped;
        }
    }
}
