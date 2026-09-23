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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.json.Json;
import javax.json.JsonObject;
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
 * @since 2.2.9
 */
final class ArchiveFingerprint {

    /**
     * Composer manifest file name.
     */
    private static final String COMPOSER = "composer.json";

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
     * @return Hex SHA-256 over sorted (path, content digest) pairs
     */
    String of(final byte[] archive) {
        final Map<String, byte[]> files = this.files(archive);
        final byte[] manifest = ArchiveFingerprint.manifest(files, this.version);
        final MessageDigest total = ArchiveFingerprint.sha256();
        for (final Map.Entry<String, byte[]> file : new TreeMap<>(files).entrySet()) {
            final String[] parts = file.getKey().split("/");
            final byte[] content = COMPOSER.equals(parts[parts.length - 1])
                ? manifest : file.getValue();
            total.update(file.getKey().getBytes(StandardCharsets.UTF_8));
            total.update((byte) 0);
            total.update(ArchiveFingerprint.sha256().digest(content));
        }
        return HexFormat.of().formatHex(total.digest());
    }

    /**
     * Read all regular files of the archive, in archive order.
     *
     * @param archive Archive bytes
     * @return Path to content
     */
    private Map<String, byte[]> files(final byte[] archive) {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        try (ArchiveInputStream<?> in = this.open(archive)) {
            ArchiveEntry entry = in.getNextEntry();
            while (entry != null) {
                if (!entry.isDirectory()) {
                    files.put(entry.getName(), ArchiveFingerprint.readAll(in));
                }
                entry = in.getNextEntry();
            }
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return files;
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
     * The manifest the repository stores: the first composer.json with the
     * resolved version.
     *
     * @param files Archive files in archive order
     * @param version Resolved version
     * @return Manifest bytes, empty when the archive has no composer.json
     */
    private static byte[] manifest(final Map<String, byte[]> files, final String version) {
        for (final Map.Entry<String, byte[]> file : files.entrySet()) {
            final String[] parts = file.getKey().split("/");
            if (COMPOSER.equals(parts[parts.length - 1])) {
                final JsonObject json = Json.createReader(
                    new ByteArrayInputStream(file.getValue())
                ).readObject();
                return Json.createObjectBuilder(json)
                    .add(JsonPackage.VRSN, version)
                    .build()
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            }
        }
        return new byte[0];
    }

    /**
     * Read the current entry fully.
     *
     * @param in Entry stream
     * @return Entry bytes
     * @throws IOException On read failure
     */
    private static byte[] readAll(final InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int len = in.read(buf);
        while (len > 0) {
            out.write(buf, 0, len);
            len = in.read(buf);
        }
        return out.toByteArray();
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
}
