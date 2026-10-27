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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The one Jackson configuration every npm packument reader and writer uses:
 * ingest ({@link AbbreviatedMetadataStream}), the streamed serve
 * ({@link PackumentTransform}, {@link PackumentSkeleton},
 * {@link PackumentVersion}) and cooldown parsing of the skeleton.
 *
 * <p>Jackson's default size and depth guards are lifted because the javax.json
 * DOM used before 2.2.11 had none, so an upstream packument that parsed then
 * must still parse. Every stage using the same limits also means a document
 * that was stored can always be evaluated for cooldown: a stricter serve-side
 * parser would fail on it, and the failure must never become an unfiltered
 * serve. Jackson's streaming parser and tree builder are iterative, so deep
 * nesting does not grow the stack.</p>
 *
 * @since 2.2.11
 */
public final class PackumentJson {

    /**
     * Parser/generator factory.
     */
    public static final JsonFactory FACTORY = JsonFactory.builder()
        .streamReadConstraints(
            StreamReadConstraints.builder()
                .maxStringLength(Integer.MAX_VALUE)
                .maxNumberLength(Integer.MAX_VALUE)
                .maxNameLength(Integer.MAX_VALUE)
                .maxNestingDepth(Integer.MAX_VALUE)
                .build()
        )
        .streamWriteConstraints(
            StreamWriteConstraints.builder().maxNestingDepth(Integer.MAX_VALUE).build()
        )
        .build();

    /**
     * Mapper over {@link #FACTORY}.
     */
    public static final ObjectMapper MAPPER = new ObjectMapper(PackumentJson.FACTORY);

    private PackumentJson() {
    }
}
