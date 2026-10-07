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
package com.auto1.pantera.pypi.meta;

import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.http.log.EcsLogger;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.z.ZCompressorInputStream;
import org.apache.commons.io.IOUtils;

/**
 * Python package metadata.
 * @since 0.6
 */
public interface Metadata {

    /**
     * Read package metadata from python artifact.
     * @return Instance of {@link PackageInfo}.
     */
    PackageInfo read();

    /**
     * Parsed {@link PackageInfo} paired with the raw {@code METADATA}/
     * {@code PKG-INFO} entry bytes exactly as stored in the archive —
     * needed to persist a byte-faithful PEP 658 {@code .metadata}
     * sidecar file (re-encoding the parsed {@link PackageInfo} string
     * would not round-trip non-ASCII long-description bytes).
     *
     * @param info Parsed package info
     * @param rawMetadata Raw entry bytes as read from the archive
     */
    record Extracted(PackageInfo info, byte[] rawMetadata) {
        /**
         * Canonical constructor; defensively copies the byte array so the
         * caller cannot mutate this record's state after construction.
         *
         * @param info Parsed package info
         * @param rawMetadata Raw entry bytes as read from the archive
         */
        public Extracted {
            rawMetadata = Extracted.copyOf(rawMetadata);
        }

        /**
         * @return Defensive copy of the raw metadata bytes
         */
        @Override
        public byte[] rawMetadata() {
            return Extracted.copyOf(this.rawMetadata);
        }

        /**
         * Null-safe defensive array copy.
         * @param value Source array, may be null
         * @return A clone, or an empty array when {@code value} is null
         */
        private static byte[] copyOf(final byte[] value) {
            return value == null ? new byte[0] : value.clone();
        }
    }

    /**
     * Metadata from archive implementation.
     * @since 0.6
     */
    final class FromArchive implements Metadata {

        /**
         * Archive input stream.
         */
        private final InputStream input;

        /**
         * Name of the file.
         */
        private final String filename;

        /**
         * Ctor.
         * @param input Path to archive
         * @param filename Filename
         */
        public FromArchive(final InputStream input, final String filename) {
            this.input = input;
            this.filename = filename;
        }

        @Override
        public PackageInfo read() {
            return this.readWithMetadata().info();
        }

        /**
         * Read the parsed {@link PackageInfo} together with the raw
         * {@code METADATA}/{@code PKG-INFO} entry bytes, in a single
         * pass over the archive (PEP 658 support needs the exact bytes
         * to persist a byte-faithful {@code .metadata} sidecar file).
         *
         * @return Parsed info paired with the raw metadata entry bytes
         */
        public Extracted readWithMetadata() {
            final Extracted res;
            if (Stream.of("zip", "whl", "egg").anyMatch(this.filename::endsWith)) {
                res = this.readZipEggOrWhl();
            } else if (this.filename.endsWith("tar")) {
                res = this.readTar();
            } else if (this.filename.endsWith("tar.gz")) {
                res = this.readTarGz();
            } else if (this.filename.endsWith("tar.Z")) {
                res = this.readTarZ();
            } else if (this.filename.endsWith("tar.bz2")) {
                res = this.readBz();
            } else {
                throw new UnsupportedOperationException("Unsupported archive type");
            }
            return res;
        }

        /**
         * Reads tar.Z files.
         * @return Extracted package info + raw metadata bytes
         */
        private Extracted readTarZ() {
            try (
                ZCompressorInputStream origin = new ZCompressorInputStream(
                    new BufferedInputStream(this.input)
                )
            ) {
                return FromArchive.unpack(origin);
            } catch (final IOException | ArchiveException ex) {
                throw FromArchive.error(ex);
            }
        }

        /**
         * Reads tar.Z files.
         * @return Extracted package info + raw metadata bytes
         */
        private Extracted readBz() {
            try (
                BZip2CompressorInputStream origin = new BZip2CompressorInputStream(
                    new BufferedInputStream(this.input)
                )
            ) {
                return FromArchive.unpack(origin);
            } catch (final IOException | ArchiveException ex) {
                throw FromArchive.error(ex);
            }
        }

        /**
         * Reads metadata from zip, egg or wheel archive.
         * @return Extracted package info + raw metadata bytes
         */
        private Extracted readZipEggOrWhl() {
            try (ZipArchiveInputStream archive =
                new ZipArchiveInputStream(new BufferedInputStream(this.input))
            ) {
                return FromArchive.readArchive(archive);
            } catch (final IOException ex) {
                // B7: middle-layer log-and-rethrow — caller slice that
                // surfaces the wrapped exception to HTTP / import is the
                // boundary.
                EcsLogger.trace("com.auto1.pantera.pypi")
                    .message("Failed to read metadata from archive")
                    .eventCategory("web")
                    .eventAction("metadata_extraction")
                    .field("error.type", ex.getClass().getSimpleName())
                    .field("log.source", "application")
                    .log();
                throw FromArchive.error(ex);
            }
        }

        /**
         * Reads metadata from zip, egg or wheel archive.
         * @return Extracted package info + raw metadata bytes
         */
        private Extracted readTar() {
            try (ArchiveInputStream archive =
                new TarArchiveInputStream(new BufferedInputStream(this.input))
            ) {
                return FromArchive.readArchive(archive);
            } catch (final IOException ex) {
                throw FromArchive.error(ex);
            }
        }

        /**
         * Reads metadata from zip or tar archive.
         * @return Extracted package info + raw metadata bytes
         */
        private Extracted readTarGz() {
            try (GzipCompressorInputStream archive = new GzipCompressorInputStream(this.input);
                TarArchiveInputStream tar = new TarArchiveInputStream(archive)) {
                return FromArchive.readArchive(tar);
            } catch (final IOException ex) {
                throw FromArchive.error(ex);
            }
        }

        /**
         * Reads archive from compressor input stream, creates ArchiveInputStream and
         * calls {@link FromArchive#readArchive} to extract metadata info.
         * @param origin Origin input stream
         * @return Extracted package info + raw metadata bytes
         * @throws IOException On IO error
         * @throws ArchiveException In case on problems to unpack
         */
        private static Extracted unpack(final InputStream origin)
            throws IOException, ArchiveException {
            try (
                ArchiveInputStream archive = new ArchiveStreamFactory().createArchiveInputStream(
                    new BufferedInputStream(origin)
                )
            ) {
                return readArchive(archive);
            }
        }

        /**
         * Error.
         * @param err Original exception
         * @return IllegalArgumentException instance
         */
        private static PanteraIOException error(final Exception err) {
            return new PanteraIOException("Failed to parse python package", err);
        }

        /**
         * Reads archive, capturing both the parsed {@link PackageInfo} and
         * the raw {@code METADATA}/{@code PKG-INFO} entry bytes in one pass.
         * @param input Archive to read
         * @return Extracted package info + raw metadata bytes
         * @throws IOException On error
         */
        private static Extracted readArchive(final ArchiveInputStream input) throws IOException {
            ArchiveEntry entry;
            Optional<Extracted> res = Optional.empty();
            while ((entry = input.getNextEntry()) != null) {
                if (!input.canReadEntryData(entry) || entry.isDirectory()) {
                    continue;
                }
                if (entry.getName().contains("PKG-INFO") || entry.getName().contains("METADATA")) {
                    final byte[] raw = IOUtils.toByteArray(input);
                    res = Optional.of(
                        new Extracted(
                            new PackageInfo.FromMetadata(
                                new String(raw, StandardCharsets.US_ASCII)
                            ),
                            raw
                        )
                    );
                }
            }
            return res.orElseThrow(
                () -> new PanteraIOException("Package metadata file not found")
            );
        }

    }

}
