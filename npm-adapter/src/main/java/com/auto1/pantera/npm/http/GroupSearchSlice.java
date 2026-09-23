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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonValue;

/**
 * {@code GET /-/v1/search} on an npm group: every member is searched with
 * the same query and the results are merged in member order (hosted
 * packages first when the hosted member is declared first), one object per
 * package name, at most {@code size} objects. {@code total} is the sum of
 * the members' totals.
 *
 * @since 2.2.9
 */
public final class GroupSearchSlice implements Slice {

    /**
     * {@code size} query parameter.
     */
    private static final Pattern SIZE = Pattern.compile("(?:^|&)size=(\\d{1,5})");

    /**
     * npm's default page size.
     */
    private static final int DEFAULT_SIZE = 20;

    /**
     * JSON key of the result objects.
     */
    private static final String OBJECTS = "objects";

    /**
     * Members.
     */
    private final MemberFanout members;

    /**
     * Ctor.
     * @param names Member repository names
     * @param slices Member repository slices, same order
     */
    public GroupSearchSlice(final List<String> names, final List<Slice> slices) {
        this.members = new MemberFanout(names, slices);
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final int size = GroupSearchSlice.size(line);
        return body.asBytesFuture().thenCompose(
            ignored -> this.members.query(line, headers)
        ).thenApply(
            answers -> {
                final JsonArrayBuilder objects = Json.createArrayBuilder();
                final Set<String> seen = new HashSet<>();
                long total = 0;
                for (final JsonObject answer : answers) {
                    total += GroupSearchSlice.total(answer);
                    final JsonValue arr = answer.get(GroupSearchSlice.OBJECTS);
                    if (arr == null || arr.getValueType() != JsonValue.ValueType.ARRAY) {
                        continue;
                    }
                    for (final JsonValue obj : arr.asJsonArray()) {
                        if (seen.size() < size && GroupSearchSlice.first(obj, seen)) {
                            objects.add(obj);
                        }
                    }
                }
                return ResponseBuilder.ok()
                    .jsonBody(
                        Json.createObjectBuilder()
                            .add(GroupSearchSlice.OBJECTS, objects)
                            .add("total", total)
                            .add("time", java.time.Instant.now().toString())
                            .build()
                    )
                    .build();
            }
        );
    }

    /**
     * Whether a search object names a package not seen yet (and records it).
     * @param obj Search object
     * @param seen Package names already merged
     * @return True to keep the object
     */
    private static boolean first(final JsonValue obj, final Set<String> seen) {
        boolean keep = false;
        if (obj.getValueType() == JsonValue.ValueType.OBJECT) {
            final JsonValue pkg = obj.asJsonObject().get("package");
            if (pkg != null && pkg.getValueType() == JsonValue.ValueType.OBJECT) {
                keep = seen.add(pkg.asJsonObject().getString("name", ""));
            }
        }
        return keep;
    }

    /**
     * A member's {@code total}, or 0.
     * @param answer Member answer
     * @return Total
     */
    private static long total(final JsonObject answer) {
        final JsonValue value = answer.get("total");
        long total = 0;
        if (value instanceof JsonNumber) {
            total = ((JsonNumber) value).longValue();
        }
        return total;
    }

    /**
     * Requested page size.
     * @param line Request line
     * @return Size
     */
    private static int size(final RequestLine line) {
        final String query = line.uri().getRawQuery();
        int size = GroupSearchSlice.DEFAULT_SIZE;
        if (query != null) {
            final Matcher matcher = GroupSearchSlice.SIZE.matcher(query);
            if (matcher.find()) {
                size = Integer.parseInt(matcher.group(1));
            }
        }
        return size;
    }
}
