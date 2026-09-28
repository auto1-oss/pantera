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
package com.auto1.pantera.helm;

import com.auto1.pantera.asto.PanteraIOException;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * A .tgz archive file.
 * @since 0.2
 */
public final class TgzArchive {

    /**
     * The archive content.
     */
    private final byte[] content;

    /**
     * Chart yaml file.
     */
    private final ChartYaml chart;

    /**
     * Upper bound on a materialised {@code Chart.yaml} (1 MiB). The entry is
     * inflated from the uploaded archive and joined into one String; before
     * 2.2.9 that join was unbounded, so a highly repetitive Chart.yaml —
     * tiny once gzipped — expanded into a heap-sized String on every push
     * (resource-dos F45). Real charts are a few KB.
     */
    public static final long MAX_CHART_YAML_BYTES = 1024L * 1024L;

    /**
     * Ctor.
     * @param content Archive bytes
     */
    public TgzArchive(final byte[] content) {
        this.content = content; // NOPMD ArrayIsStoredDirectly - immutable archive holder; bytes() returns a defensive copy
        this.chart = new ChartYaml(this.file("Chart.yaml"));
    }

    /**
     * Obtain archive name.
     * @return How the archive should be named on the file system
     */
    public String name() {
        return String.format("%s-%s.tgz", this.chart.name(), this.chart.version());
    }

    /**
     * Metadata of archive.
     *
     * @param baseurl Base url.
     * @return Metadata of archive.
     */
    public Map<String, Object> metadata(final Optional<String> baseurl) {
        final Map<String, Object> meta = new HashMap<>();
        // Include chart name in path: <chart_name>/<chart_name>-<version>.tgz
        final String urlPath = String.format("%s/%s", this.chart.name(), this.name());
        meta.put(
            "urls",
            new ArrayList<>(
                Collections.singletonList(
                    String.format(
                        "%s%s",
                        baseurl.orElse(""),
                        urlPath
                    )
                )
            )
        );
        meta.put("digest", DigestUtils.sha256Hex(this.content));
        meta.putAll(this.chart.fields());
        return meta;
    }

    /**
     * Find a Chart.yaml file inside.
     * @return The Chart.yaml file.
     */
    public ChartYaml chartYaml() {
        return this.chart;
    }

    /**
     * Obtains binary content of archive.
     * @return Byte array with content of archive.
     */
    public byte[] bytes() {
        return Arrays.copyOf(this.content, this.content.length);
    }

    /**
     * Tgz size in bytes.
     * @return Size
     */
    public long size() {
        return this.content.length;
    }

    /**
     * Read the current tar entry as text, refusing it once it inflates past
     * {@link #MAX_CHART_YAML_BYTES} — the bound is on INFLATED bytes, which
     * is what a decompression bomb attacks.
     *
     * @param taris Tar stream positioned at the entry
     * @param name Entry name, for the error message
     * @return Entry text with line endings normalised to {@code \n}
     * @throws IOException On read failure or when the entry exceeds the cap
     */
    private static String readBounded(
        final TarArchiveInputStream taris, final String name
    ) throws IOException {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final byte[] chunk = new byte[8192];
        int read;
        while ((read = taris.read(chunk)) != -1) {
            if (out.size() + read > MAX_CHART_YAML_BYTES) {
                throw new IOException(
                    String.format(
                        "'%s' inflates past the %d-byte limit", name, MAX_CHART_YAML_BYTES
                    )
                );
            }
            out.write(chunk, 0, read);
        }
        return new BufferedReader(
            new InputStreamReader(
                new ByteArrayInputStream(out.toByteArray()), java.nio.charset.StandardCharsets.UTF_8
            )
        ).lines().collect(Collectors.joining("\n"));
    }

    /**
     * Obtain file by name.
     *
     * @param name The name of a file.
     * @return The file content.
     */
    private String file(final String name) {
        try {
            if (!this.isGzipFormat()) {
                throw new PanteraIOException(
                    new IOException("Input is not in the .gz format")
                );
            }
            final TarArchiveInputStream taris = new TarArchiveInputStream(
                new GzipCompressorInputStream(new ByteArrayInputStream(this.content))
            );
            TarArchiveEntry entry;
            while ((entry = taris.getNextTarEntry()) != null) {
                if (entry.getName().endsWith(name)) {
                    return TgzArchive.readBounded(taris, name);
                }
            }
            throw new IllegalStateException(String.format("'%s' file wasn't found", name));
        } catch (final IOException exc) {
            throw new PanteraIOException(exc);
        }
    }

    /**
     * Check if the content is a valid gzip format.
     * @return True if valid gzip format, false otherwise
     */
    private boolean isGzipFormat() {
        if (this.content.length < 2) {
            return false;
        }
        // Check gzip magic number: 0x1f, 0x8b
        return (this.content[0] & 0xFF) == 0x1f && (this.content[1] & 0xFF) == 0x8b;
    }
}
