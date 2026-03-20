/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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
@SuppressWarnings({
    "PMD.ArrayIsStoredDirectly",
    "PMD.AssignmentInOperand"
})
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
     * Ctor.
     * @param content The archive content.
     */
    public TgzArchive(final byte[] content) {
        this.content = content;
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
                    return new BufferedReader(new InputStreamReader(taris))
                        .lines()
                        .collect(Collectors.joining("\n"));
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
