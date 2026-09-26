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
package com.auto1.pantera.composer.http;

import com.auto1.pantera.composer.JsonPackage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonReader;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * Content fingerprint of a Composer package archive, independent of the
 * container bytes.
 *
 * <p>A stored archive is not byte-identical to the upload: the repository
 * rewrites it with the resolved {@code version} in {@code composer.json},
 * and re-packing stamps new entry timestamps. The fingerprint therefore
 * covers what the package contains — every file's path and content, with
 * every {@code composer.json} replaced by the first one plus the resolved
 * version, exactly as the repository stores it — so the stored archive and
 * a re-upload of the same package produce the same value.</p>
 *
 * <p>Entries are streamed: each file's content is hashed while it is
 * inflated and only its digest is kept, plus the bytes of the first
 * {@code composer.json} (at most {@link #MAX_MANIFEST}). The walk stops at
 * {@link #MAX_BYTES} inflated bytes or {@link #MAX_ENTRIES} entries, so a
 * highly compressible archive cannot exhaust the heap; an archive past a
 * limit, or one that is corrupt, has no fingerprint.</p>
 *
 * @since 2.2.9
 */
final class ArchiveFingerprint {

    /**
     * Composer manifest file name.
     */
    private static final String COMPOSER = "composer.json";

    /**
     * Most inflated bytes read from one archive.
     */
    private static final long MAX_BYTES = 256L * 1024 * 1024;

    /**
     * Most entries read from one archive.
     */
    private static final int MAX_ENTRIES = 65_536;

    /**
     * Largest composer.json kept in memory.
     */
    private static final int MAX_MANIFEST = 1024 * 1024;

    /**
     * Whether the archive is a ZIP (else TAR.GZ).
     */
    private final boolean zip;

    /**
     * Resolved package version.
     */
    private final String version;

    /**
     * Ctor.
     *
     * @param zip True for ZIP, false for TAR.GZ
     * @param version Resolved version the repository writes into composer.json
     */
    ArchiveFingerprint(final boolean zip, final String version) {
        this.zip = zip;
        this.version = version;
    }

    /**
     * Fingerprint of an archive.
     *
     * @param archive Archive bytes
     * @return Hex SHA-256 over sorted (path, content digest) pairs; empty when
     *  the archive is corrupt or past a limit
     */
    Optional<String> of(final byte[] archive) {
        Optional<String> result;
        try {
            result = Optional.of(this.walk(archive).digest(this.version));
        } catch (final IOException | JsonException | IllegalStateException
            | IllegalArgumentException ex) {
            result = Optional.empty();
        }
        return result;
    }

    /**
     * Stream all regular files of the archive into their digests.
     *
     * @param archive Archive bytes
     * @return Collected digests
     * @throws IOException On a malformed archive or a limit being exceeded
     */
    private Digests walk(final byte[] archive) throws IOException {
        final Digests digests = new Digests();
        try (ArchiveInputStream<?> in = this.open(archive)) {
            ArchiveEntry entry = in.getNextEntry();
            int count = 0;
            while (entry != null) {
                count += 1;
                if (count > ArchiveFingerprint.MAX_ENTRIES) {
                    throw new IOException("Too many archive entries");
                }
                if (!entry.isDirectory()) {
                    digests.read(entry.getName(), in);
                }
                entry = in.getNextEntry();
            }
        }
        return digests;
    }

    /**
     * Open the archive stream.
     *
     * @param archive Archive bytes
     * @return Entry stream
     * @throws IOException On a malformed archive
     */
    private ArchiveInputStream<?> open(final byte[] archive) throws IOException {
        if (this.zip) {
            return new ZipArchiveInputStream(new ByteArrayInputStream(archive));
        }
        return new TarArchiveInputStream(
            new GzipCompressorInputStream(new ByteArrayInputStream(archive))
        );
    }

    /**
     * Whether an entry path names a composer.json.
     *
     * @param path Entry path
     * @return True for {@code composer.json} at any depth
     */
    private static boolean manifest(final String path) {
        final String[] parts = path.split("/");
        return COMPOSER.equals(parts[parts.length - 1]);
    }

    /**
     * New SHA-256 digest.
     *
     * @return Digest
     */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Per-path content digests of one archive, collected while streaming.
     *
     * @since 2.2.9
     */
    private static final class Digests {

        /**
         * Path to content digest; {@code null} for a composer.json, whose
         * digest is the normalised manifest's.
         */
        private final Map<String, byte[]> files = new TreeMap<>();

        /**
         * Read buffer.
         */
        private final byte[] buf = new byte[8192];

        /**
         * First composer.json, if seen.
         */
        private byte[] first;

        /**
         * Inflated bytes read so far.
         */
        private long total;

        /**
         * Consume the current entry.
         *
         * @param path Entry path
         * @param in Entry stream
         * @throws IOException On read failure or a limit being exceeded
         */
        void read(final String path, final InputStream in) throws IOException {
            if (ArchiveFingerprint.manifest(path)) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                this.drain(in, (chunk, len) -> {
                    if (this.first == null) {
                        if (out.size() + len > ArchiveFingerprint.MAX_MANIFEST) {
                            throw new IOException("composer.json is too large");
                        }
                        out.write(chunk, 0, len);
                    }
                });
                if (this.first == null) {
                    this.first = out.toByteArray();
                }
                this.files.put(path, null);
            } else {
                final MessageDigest digest = ArchiveFingerprint.sha256();
                this.drain(in, (chunk, len) -> digest.update(chunk, 0, len));
                this.files.put(path, digest.digest());
            }
        }

        /**
         * Fingerprint of the collected digests.
         *
         * @param version Resolved version
         * @return Hex SHA-256
         */
        String digest(final String version) {
            final byte[] manifest = ArchiveFingerprint.sha256().digest(this.normalised(version));
            final MessageDigest total = ArchiveFingerprint.sha256();
            for (final Map.Entry<String, byte[]> file : this.files.entrySet()) {
                total.update(file.getKey().getBytes(StandardCharsets.UTF_8));
                total.update((byte) 0);
                total.update(file.getValue() == null ? manifest : file.getValue());
            }
            return HexFormat.of().formatHex(total.digest());
        }

        /**
         * The manifest the repository stores: the first composer.json with
         * the resolved version.
         *
         * @param version Resolved version
         * @return Manifest bytes, empty when the archive has no composer.json
         */
        private byte[] normalised(final String version) {
            if (this.first == null) {
                return new byte[0];
            }
            try (JsonReader reader = Json.createReader(new ByteArrayInputStream(this.first))) {
                return Json.createObjectBuilder(reader.readObject())
                    .add(JsonPackage.VRSN, version)
                    .build()
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            }
        }

        /**
         * Read the current entry to its end, counting against the limit.
         *
         * @param in Entry stream
         * @param sink Receiver of each chunk
         * @throws IOException On read failure or the size limit
         */
        private void drain(final InputStream in, final Sink sink) throws IOException {
            int len = in.read(this.buf);
            while (len >= 0) {
                this.total += len;
                if (this.total > ArchiveFingerprint.MAX_BYTES) {
                    throw new IOException("Archive content is too large to verify");
                }
                if (len > 0) {
                    sink.accept(this.buf, len);
                }
                len = in.read(this.buf);
            }
        }
    }

    /**
     * Receiver of streamed entry chunks.
     *
     * @since 2.2.9
     */
    @FunctionalInterface
    private interface Sink {
        /**
         * Accept a chunk.
         *
         * @param chunk Buffer
         * @param len Valid bytes
         * @throws IOException On failure
         */
        void accept(byte[] chunk, int len) throws IOException;
    }
}
