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
package com.auto1.pantera.nuget;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.test.TestResource;

/**
 * Newton.Json package resource.
 *
 * @since 0.1
 */
public final class NewtonJsonResource {

    /**
     * Resource name.
     */
    private final String name;

    /**
     * Ctor.
     *
     * @param name Resource name.
     */
    public NewtonJsonResource(final String name) {
        this.name = name;
    }

    /**
     * Reads binary data.
     *
     * @return Binary data.
     */
    public Content content() {
        return new Content.From(this.bytes());
    }

    /**
     * Reads binary data.
     *
     * @return Binary data.
     */
    public byte[] bytes() {
        return new TestResource(String.format("newtonsoft.json/12.0.3/%s", this.name)).asBytes();
    }

    /**
     * Same archive repacked with one extra entry: an upload of the same
     * package id and version whose bytes differ from {@link #bytes()}.
     *
     * @return Binary data.
     */
    public byte[] repacked() {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (
            java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(this.bytes())
            );
            java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)
        ) {
            java.util.zip.ZipEntry entry = in.getNextEntry();
            while (entry != null) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                in.transferTo(zip);
                zip.closeEntry();
                entry = in.getNextEntry();
            }
            zip.putNextEntry(new java.util.zip.ZipEntry("extra.txt"));
            zip.write("rebuilt".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (final java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        return out.toByteArray();
    }
}
