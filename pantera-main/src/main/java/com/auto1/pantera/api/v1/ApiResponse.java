/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api.v1;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.util.List;

public final class ApiResponse {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private ApiResponse() {
    }

    public static JsonObject error(final int status, final String error, final String message) {
        return new JsonObject()
            .put("error", error)
            .put("message", message)
            .put("status", status);
    }

    public static void sendError(final RoutingContext ctx, final int status,
        final String error, final String message) {
        ctx.response()
            .setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(error(status, error, message).encode());
    }

    public static JsonObject paginated(final JsonArray items, final int page,
        final int size, final int total) {
        return new JsonObject()
            .put("items", items)
            .put("page", page)
            .put("size", size)
            .put("total", total)
            .put("hasMore", (long) (page + 1) * size < total);
    }

    public static <T> JsonArray sliceToArray(final List<T> all, final int page, final int size) {
        final int offset = page * size;
        final JsonArray arr = new JsonArray();
        for (int i = offset; i < Math.min(offset + size, all.size()); i++) {
            arr.add(all.get(i));
        }
        return arr;
    }

    public static int clampSize(final int requested) {
        if (requested <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(requested, MAX_SIZE);
    }

    public static int intParam(final String value, final int def) {
        if (value == null || value.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException ex) {
            return def;
        }
    }
}
