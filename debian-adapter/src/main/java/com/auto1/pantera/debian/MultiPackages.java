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

import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.debian.metadata.ControlField;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

/**
 * MultiDebian merges metadata.
 * @since 0.6
 */
public interface MultiPackages {

    /**
     * Merges provided indexes.
     * @param items Items to merge
     * @param res Output stream with merged data
     * @throws com.auto1.pantera.asto.PanteraIOException On IO error
     */
    void merge(Collection<InputStream> items, OutputStream res);

    /**
     * Implementation of {@link MultiPackages} that merges Packages indexes checking for duplicates
     * and writes list of the unique Packages to the output stream. Implementation
     * does not close input or output streams, these operations should be made from the outside.
     * @since 0.6
     */
    final class Unique implements MultiPackages {

        @Override
        public void merge(final Collection<InputStream> items, final OutputStream res) {
            try (GZIPOutputStream gop = new GZIPOutputStream(new NonClosingOutputStream(res))) {
                final Set<Pair<String, String>> packages = new HashSet<>(items.size());
                for (final InputStream inp : items) {
                    Unique.appendPackages(gop, inp, packages);
                }
                gop.finish();
            } catch (final IOException err) {
                throw new PanteraIOException(err);
            }
        }

        /**
         * Appends items from provided InputStream to OutputStream, duplicated packages are not
         * appended.
         * @param out OutputStream to write the result
         * @param inp InputStream to read Packages index from
         * @param packages Map with the appended packages
         */
        @SuppressWarnings("PMD.CyclomaticComplexity")
        private static void appendPackages(
            final OutputStream out, final InputStream inp, final Set<Pair<String, String>> packages
        ) {
            try (
                GZIPInputStream gis = new GZIPInputStream(new NonClosingInputStream(inp));
                BufferedReader rdr =
                    new BufferedReader(new InputStreamReader(gis, StandardCharsets.UTF_8))
            ) {
                String line;
                StringBuilder item = new StringBuilder();
                do {
                    line = rdr.readLine();
                    if ((line == null || line.isEmpty()) && item.length() > 0) {
                        final Pair<String, String> pair = new ImmutablePair<>(
                            new ControlField.Package().value(item.toString()).get(0),
                            new ControlField.Version().value(item.toString()).get(0)
                        );
                        if (!packages.contains(pair)) {
                            out.write(
                                item.append('\n').toString().getBytes(StandardCharsets.UTF_8)
                            );
                            packages.add(pair);
                        }
                        item = new StringBuilder();
                    } else if (line != null && !line.isEmpty()) {
                        item.append(line).append('\n');
                    }
                } while (line != null);
            } catch (final IOException err) {
                throw new PanteraIOException(err);
            }
        }

        /**
         * Wraps an {@link OutputStream} so that {@link #close()} is a no-op – ownership
         * of the underlying stream is kept by the caller of
         * {@link Unique#merge(Collection, OutputStream)}.
         * @since 2.2.0
         */
        private static final class NonClosingOutputStream extends FilterOutputStream {
            NonClosingOutputStream(final OutputStream out) {
                super(out);
            }

            @Override
            public void write(final byte[] buf, final int off, final int len) throws IOException {
                this.out.write(buf, off, len);
            }

            @Override
            public void close() throws IOException {
                this.flush();
            }
        }

        /**
         * Wraps an {@link InputStream} so that {@link #close()} is a no-op – ownership
         * of the underlying stream is kept by the caller of
         * {@link Unique#merge(Collection, OutputStream)}.
         * @since 2.2.0
         */
        private static final class NonClosingInputStream extends FilterInputStream {
            NonClosingInputStream(final InputStream in) {
                super(in);
            }

            @Override
            public void close() {
                // Intentionally no-op – the underlying stream is owned by the caller.
            }
        }
    }

}
