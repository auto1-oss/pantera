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
package com.auto1.pantera.npm;

import com.auto1.pantera.PanteraException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test for the npm tar-entry allocation
 * (resource-dos F16).
 *
 * <p>Before 2.2.9 {@code TgzArchive.JsonFromStream.json()} allocated
 * {@code new byte[(int) entry.getSize()]} straight from the tar HEADER of
 * the {@code package.json} entry — attacker-controlled metadata — before
 * reading a byte of it. A crafted tarball whose header declares a
 * multi-gigabyte {@code package.json} (with a few real bytes behind it)
 * drove a heap allocation of that size on every {@code npm publish},
 * killing the registry on an {@code -XX:+ExitOnOutOfMemoryError} JVM. The
 * declared entry size must be bounded and rejected up front.</p>
 *
 * @since 2.2.9
 */
final class TgzArchiveEntrySizeTest {

    /**
     * Forged header size: far above any real package.json, large enough that
     * the old code's allocation is unmistakable, small enough that the RED
     * run fails on the assertion rather than crashing the test JVM.
     */
    private static final long FORGED = 256L * 1024L * 1024L;

    @Test
    void packageJsonEntryDeclaringHugeSizeIsRejectedBeforeAllocation() throws IOException {
        final byte[] real = "{\"name\":\"x\",\"version\":\"1.0.0\"}"
            .getBytes(StandardCharsets.UTF_8);
        final byte[] tgz = gzip(tarWithForgedSize("package/package.json", FORGED, real));
        final PanteraException failure = Assertions.assertThrows(
            PanteraException.class,
            () -> new TgzArchive.JsonFromStream(new ByteArrayInputStream(tgz)).json()
        );
        MatcherAssert.assertThat(
            "an oversized package.json entry must be refused by the size limit, "
                + "not allocated from the forged header",
            failure.getMessage(), new StringContains("limit")
        );
    }

    /**
     * A minimal ustar archive: one file entry whose HEADER size field is
     * forged to {@code declared} while only {@code payload} bytes follow.
     */
    private static byte[] tarWithForgedSize(
        final String name, final long declared, final byte[] payload
    ) throws IOException {
        final byte[] header = new byte[512];
        put(header, 0, name);
        put(header, 100, "0000644\0");
        put(header, 108, "0000000\0");
        put(header, 116, "0000000\0");
        put(header, 124, String.format("%011o\0", declared));
        put(header, 136, "00000000000\0");
        header[156] = '0';
        put(header, 257, "ustar\0");
        put(header, 263, "00");
        // Checksum: sum of all header bytes with the checksum field as spaces.
        for (int idx = 148; idx < 156; idx = idx + 1) {
            header[idx] = ' ';
        }
        long sum = 0;
        for (final byte value : header) {
            sum += value & 0xFF;
        }
        put(header, 148, String.format("%06o\0 ", sum));
        final ByteArrayOutputStream tar = new ByteArrayOutputStream();
        tar.write(header);
        tar.write(payload);
        // Pad the data block, then two empty end-of-archive blocks.
        tar.write(new byte[512 - payload.length % 512]);
        tar.write(new byte[1024]);
        return tar.toByteArray();
    }

    private static void put(final byte[] header, final int offset, final String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    private static byte[] gzip(final byte[] raw) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }
}
