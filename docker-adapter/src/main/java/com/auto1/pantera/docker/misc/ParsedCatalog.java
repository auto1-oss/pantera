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
package com.auto1.pantera.docker.misc;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Catalog;

import javax.json.Json;
import javax.json.JsonString;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Parsed {@link Catalog} that is capable of extracting repository names list
 * from origin {@link Catalog}.
 */
public final class ParsedCatalog implements Catalog {

    /**
     * Origin catalog.
     */
    private final Catalog origin;

    /**
     * @param origin Origin catalog.
     */
    public ParsedCatalog(final Catalog origin) {
        this.origin = origin;
    }

    @Override
    public Content json() {
        return this.origin.json();
    }

    /**
     * Get repository names list from origin catalog.
     *
     * @return Repository names list.
     */
    public CompletionStage<List<String>> repos() {
        return this.origin.json().asBytesFuture().thenApply(
            bytes -> Json.createReader(new ByteArrayInputStream(bytes)).readObject()
            ).thenApply(root -> root.getJsonArray("repositories"))
            .thenApply(
                repos -> repos.getValuesAs(JsonString.class).stream()
                .map(JsonString::getString)
                .map(ImageRepositoryName::validate)
                .toList()
        );
    }
}
