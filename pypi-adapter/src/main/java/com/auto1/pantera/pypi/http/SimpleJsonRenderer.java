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
package com.auto1.pantera.pypi.http;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;

/**
 * Renders PEP 691 (v1.1) JSON Simple Repository API responses.
 * Includes upload-time per PEP 700.
 */
public final class SimpleJsonRenderer {

    private SimpleJsonRenderer() {
    }

    /**
     * Render a package detail page as PEP 691 JSON.
     * @param packageName Normalized package name
     * @param files List of file entries
     * @return JSON string
     */
    public static String render(final String packageName, final List<FileEntry> files) {
        final JsonArrayBuilder filesArray = Json.createArrayBuilder();
        for (final FileEntry file : files) {
            final JsonObjectBuilder entry = Json.createObjectBuilder()
                .add("filename", file.filename())
                .add("url", file.url() + "#sha256=" + file.sha256())
                .add("hashes", Json.createObjectBuilder().add("sha256", file.sha256()));
            if (file.requiresPython() != null && !file.requiresPython().isEmpty()) {
                entry.add("requires-python", file.requiresPython());
            }
            if (file.uploadTime() != null) {
                entry.add("upload-time", file.uploadTime().toString());
            }
            entry.add("yanked", file.yanked());
            if (file.yanked() && file.yankedReason().isPresent()) {
                entry.add("yanked-reason", file.yankedReason().get());
            }
            if (file.distInfoMetadata().isPresent()) {
                entry.add("data-dist-info-metadata",
                    Json.createObjectBuilder().add("sha256", file.distInfoMetadata().get()));
            }
            filesArray.add(entry);
        }
        return Json.createObjectBuilder()
            .add("meta", Json.createObjectBuilder().add("api-version", "1.1"))
            .add("name", packageName)
            .add("files", filesArray)
            .build()
            .toString();
    }

    /**
     * A file entry for the PEP 691 JSON response.
     */
    public record FileEntry(
        String filename,
        String url,
        String sha256,
        String requiresPython,
        Instant uploadTime,
        boolean yanked,
        Optional<String> yankedReason,
        Optional<String> distInfoMetadata
    ) {}
}
