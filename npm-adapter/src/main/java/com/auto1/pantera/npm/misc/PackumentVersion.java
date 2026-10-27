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
package com.auto1.pantera.npm.misc;

import com.auto1.pantera.asto.Content;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * One version's manifest ({@code versions[<version>]}) streamed out of a
 * stored packument; nothing else of the document is kept.
 *
 * @since 2.2.11
 */
public final class PackumentVersion {

    /**
     * Mapper.
     */
    private static final ObjectMapper MAPPER = PackumentJson.MAPPER;

    /**
     * Stored packument.
     */
    private final Content packument;

    /**
     * Version to extract.
     */
    private final String version;

    /**
     * Ctor.
     *
     * @param packument Stored packument
     * @param version Version to extract
     */
    public PackumentVersion(final Content packument, final String version) {
        this.packument = packument;
        this.version = version;
    }

    /**
     * Extract the manifest.
     *
     * @return Manifest, empty when the packument has no such version
     */
    public CompletableFuture<Optional<JsonNode>> manifest() {
        return new SmallJson(
            new PackumentTransform(
                this.packument,
                new PackumentTransform.Rules(
                    Map.of("versions", PackumentTransform.Section.MEMBERS),
                    PackumentTransform.Section.SKIP,
                    Map.of("versions", this.version::equals),
                    Map.of(),
                    Optional.empty()
                )
            )
        ).bytes().thenApply(
            bytes -> {
                final Optional<JsonNode> result;
                if (bytes.length == 0) {
                    result = Optional.empty();
                } else {
                    result = Optional.ofNullable(
                        PackumentVersion.read(bytes).path("versions").get(this.version)
                    );
                }
                return result;
            }
        );
    }

    private static JsonNode read(final byte[] bytes) {
        try {
            return MAPPER.readTree(bytes);
        } catch (final java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
}
