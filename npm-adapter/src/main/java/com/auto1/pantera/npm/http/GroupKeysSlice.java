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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonValue;

/**
 * {@code GET /-/npm/v1/keys} on an npm group: the union of every member's
 * registry signing keys, so {@code npm audit signatures} can verify both
 * packages signed by a hosted member (Pantera's own key) and packages
 * relayed from an upstream registry (its keys). Keys are deduplicated by
 * {@code keyid}, first member first.
 *
 * @since 2.2.9
 */
public final class GroupKeysSlice implements Slice {

    /**
     * JSON key of the keys array.
     */
    private static final String KEYS = "keys";

    /**
     * Members.
     */
    private final MemberFanout members;

    /**
     * Ctor.
     * @param names Member repository names
     * @param slices Member repository slices, same order
     */
    public GroupKeysSlice(final List<String> names, final List<Slice> slices) {
        this.members = new MemberFanout(names, slices);
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        return body.asBytesFuture().thenCompose(
            ignored -> this.members.query(line, headers)
        ).thenApply(
            answers -> {
                final JsonArrayBuilder keys = Json.createArrayBuilder();
                final Set<String> seen = new HashSet<>();
                for (final JsonObject answer : answers) {
                    final JsonValue arr = answer.get(GroupKeysSlice.KEYS);
                    if (arr == null || arr.getValueType() != JsonValue.ValueType.ARRAY) {
                        continue;
                    }
                    for (final JsonValue key : arr.asJsonArray()) {
                        if (key.getValueType() == JsonValue.ValueType.OBJECT
                            && seen.add(key.asJsonObject().getString("keyid", key.toString()))) {
                            keys.add(key);
                        }
                    }
                }
                return ResponseBuilder.ok()
                    .jsonBody(Json.createObjectBuilder().add(GroupKeysSlice.KEYS, keys).build())
                    .build();
            }
        );
    }
}
