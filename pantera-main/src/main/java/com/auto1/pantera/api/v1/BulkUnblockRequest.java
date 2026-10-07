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
package com.auto1.pantera.api.v1;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;

/**
 * Body of {@code POST /api/v1/cooldown/unblock}: one to {@link #MAX_ITEMS}
 * distinct {@code {repo, artifact, version}} items.
 * @since 2.2.10
 */
final class BulkUnblockRequest {

    /**
     * Upper bound on items per request.
     */
    static final int MAX_ITEMS = 500;

    /**
     * Raw JSON body.
     */
    private final String body;

    /**
     * Ctor.
     * @param body Raw JSON body, may be null
     */
    BulkUnblockRequest(final String body) {
        this.body = body;
    }

    /**
     * Parsed, de-duplicated items in first-seen order.
     * @return Items
     * @throws IllegalArgumentException With the message to answer as 400
     */
    List<Item> items() {
        if (this.body == null || this.body.isBlank()) {
            throw new IllegalArgumentException("JSON body is required");
        }
        final JsonObject obj;
        try {
            obj = Json.createReader(new StringReader(this.body)).readObject();
        } catch (final RuntimeException ex) {
            throw new IllegalArgumentException("Invalid JSON body", ex);
        }
        final JsonValue raw = obj.get("items");
        if (raw == null || raw.getValueType() != JsonValue.ValueType.ARRAY) {
            throw new IllegalArgumentException("items array is required");
        }
        final JsonArray arr = raw.asJsonArray();
        if (arr.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        if (arr.size() > MAX_ITEMS) {
            throw new IllegalArgumentException(
                String.format("items must contain at most %d entries", MAX_ITEMS)
            );
        }
        final Set<Item> seen = new LinkedHashSet<>();
        for (final JsonValue val : arr) {
            if (val.getValueType() != JsonValue.ValueType.OBJECT) {
                throw new IllegalArgumentException("each item must be an object");
            }
            final JsonObject item = val.asJsonObject();
            seen.add(
                new Item(
                    BulkUnblockRequest.required(item, "repo"),
                    BulkUnblockRequest.required(item, "artifact"),
                    BulkUnblockRequest.required(item, "version")
                )
            );
        }
        return new ArrayList<>(seen);
    }

    private static String required(final JsonObject item, final String key) {
        final String val = item.getString(key, "").trim();
        if (val.isEmpty()) {
            throw new IllegalArgumentException(
                String.format("%s is required on every item", key)
            );
        }
        return val;
    }

    /**
     * One artifact version in one repository.
     * @param repo Repository name
     * @param artifact Artifact as listed under package_name (or groupId:artifactId)
     * @param version Version
     */
    record Item(String repo, String artifact, String version) {
    }
}
