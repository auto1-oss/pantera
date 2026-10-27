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
package com.auto1.pantera.npm.proxy;

import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Registry-shaped packument generated part by part, so a test can stream a
 * document of tens of megabytes without ever holding it — and know the
 * SHA-256 and size of its cached (tarball-rewritten) form.
 *
 * @since 2.2.11
 */
final class SyntheticPackument {

    /**
     * Time entries per generated part.
     */
    private static final int TIMES_PER_PART = 500;

    /**
     * Package name.
     */
    private final String pkg;

    /**
     * Number of versions.
     */
    private final int versions;

    /**
     * Per-version filler (escaped, non-ASCII text a parser must skip).
     */
    private final String filler;

    /**
     * Ctor.
     *
     * @param pkg Package name, possibly scoped
     * @param versions Number of versions
     * @param padding Approximate filler characters per version
     */
    SyntheticPackument(final String pkg, final int versions, final int padding) {
        this.pkg = pkg;
        this.versions = versions;
        final String unit = "Lorem \\\"ipsum\\\" \\\\ dolor – \\u00e9t\\n ";
        this.filler = unit.repeat(Math.max(1, padding / unit.length()));
    }

    /**
     * Number of versions.
     *
     * @return Count
     */
    int versions() {
        return this.versions;
    }

    /**
     * Upstream form, one part per chunk.
     *
     * @return Lazily generated chunks
     */
    Flowable<ByteBuffer> upstream() {
        return Flowable.generate(
            () -> 0,
            (idx, emitter) -> {
                if (idx == this.parts()) {
                    emitter.onComplete();
                } else {
                    emitter.onNext(
                        ByteBuffer.wrap(this.part(idx, false).getBytes(StandardCharsets.UTF_8))
                    );
                }
                return idx + 1;
            }
        );
    }

    /**
     * Upstream form as one String (only for small instances).
     *
     * @return Document
     */
    String document() {
        return this.join(false);
    }

    /**
     * Cached form as one String (only for small instances).
     *
     * @return Document with relative tarballs
     */
    String cached() {
        return this.join(true);
    }

    /**
     * SHA-256 hex of the cached form, computed part by part.
     *
     * @return Hex digest
     * @throws Exception Never in practice
     */
    String cachedSha256() throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int idx = 0; idx < this.parts(); idx += 1) {
            digest.update(this.part(idx, true).getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Size of the cached form, computed part by part.
     *
     * @return Bytes
     */
    long cachedSize() {
        long size = 0L;
        for (int idx = 0; idx < this.parts(); idx += 1) {
            size += this.part(idx, true).getBytes(StandardCharsets.UTF_8).length;
        }
        return size;
    }

    /**
     * Whole document.
     *
     * @param cached Cached (rewritten) form
     * @return Document
     */
    private String join(final boolean cached) {
        final StringBuilder doc = new StringBuilder();
        for (int idx = 0; idx < this.parts(); idx += 1) {
            doc.append(this.part(idx, cached));
        }
        return doc.toString();
    }

    /**
     * Number of parts: header, versions, time chunks, trailer.
     *
     * @return Count
     */
    private int parts() {
        return this.versions + this.timeParts() + 2;
    }

    /**
     * Number of time parts.
     *
     * @return Count
     */
    private int timeParts() {
        return (this.versions + TIMES_PER_PART - 1) / TIMES_PER_PART;
    }

    /**
     * One part.
     *
     * @param idx Part index
     * @param cached Cached (rewritten) form
     * @return Text
     */
    private String part(final int idx, final boolean cached) {
        final String text;
        if (idx == 0) {
            text = String.format(
                "{\"_id\":\"%s\",\"_rev\":\"7-abc\",\"name\":\"%s\",\"dist-tags\":{\"latest\":\"1.0.%d\"},\"versions\":{",
                this.pkg, this.pkg, this.versions - 1
            );
        } else if (idx <= this.versions) {
            text = this.version(idx - 1, cached);
        } else if (idx <= this.versions + this.timeParts()) {
            text = this.times(idx - this.versions - 1);
        } else {
            text = "},\"readme\":\"" + this.filler + "\",\"license\":\"MIT\"}";
        }
        return text;
    }

    /**
     * One version entry.
     *
     * @param num Version number
     * @param cached Cached (rewritten) form
     * @return Text
     */
    private String version(final int num, final boolean cached) {
        final String base = this.pkg.substring(this.pkg.lastIndexOf('/') + 1);
        final String path = String.format("%s/-/%s-1.0.%d.tgz", this.pkg, base, num);
        final String tarball;
        if (cached) {
            tarball = "/" + path;
        } else {
            tarball = "https://registry.npmjs.org/" + path;
        }
        return String.format(
            "%s\"1.0.%d\":{\"name\":\"%s\",\"version\":\"1.0.%d\",\"description\":\"%s\","
                + "\"dependencies\":{\"debug\":\"^4.%d.0\",\"ms\":\"2.1.%d\"},"
                + "\"scripts\":{\"test\":\"echo \\\"tarball\\\": none\"},"
                + "\"dist\":{\"shasum\":\"%040d\",\"integrity\":\"sha512-%d==\",\"tarball\":\"%s\"},"
                + "\"_npmUser\":{\"name\":\"bot\"}}",
            num == 0 ? "" : ",", num, this.pkg, num, this.filler, num % 7, num % 5, num, num,
            tarball
        );
    }

    /**
     * One time part.
     *
     * @param part Time part index
     * @return Text
     */
    private String times(final int part) {
        final StringBuilder text = new StringBuilder();
        if (part == 0) {
            text.append("},\"time\":{\"created\":\"2020-01-01T00:00:00.000Z\",")
                .append("\"modified\":\"2024-06-01T12:00:00.000Z\"");
        }
        final int end = Math.min(this.versions, (part + 1) * TIMES_PER_PART);
        for (int num = part * TIMES_PER_PART; num < end; num += 1) {
            text.append(String.format(",\"1.0.%d\":\"2021-01-01T00:00:%02d.000Z\"", num, num % 60));
        }
        return text.toString();
    }
}
