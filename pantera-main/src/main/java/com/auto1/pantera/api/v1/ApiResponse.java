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
        if (status >= 500) {
            // Every 5xx the API answers leaves its cause in the log, joined
            // to the request by trace.id. A literal message (e.g. "Database
            // not configured") is fine to return as is; exception text goes
            // through the Throwable overload below and is never returned.
            ApiResponse.serverErrorLog(ctx, status, error)
                .field("event.reason", error.toLowerCase(java.util.Locale.ROOT))
                .message("API request failed with " + status + ": " + message)
                .log();
        }
        ctx.response()
            .setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(error(status, error, message).encode());
    }

    /**
     * Answer a 5xx caused by {@code cause}: the throwable is logged
     * (error.type / error.message / error.stack_trace, correlated by
     * trace.id) and the client gets a generic message only — exception
     * text can carry SQL, internal host names and file paths.
     *
     * @param ctx Routing context
     * @param status HTTP status (5xx)
     * @param error Error code
     * @param cause Failure behind the answer
     */
    public static void sendError(final RoutingContext ctx, final int status,
        final String error, final Throwable cause) {
        ApiResponse.sendError(ctx, status, error, "Internal server error", cause);
    }

    /**
     * Answer a 5xx caused by {@code cause} with a fixed, client-friendly
     * {@code message} (e.g. "Sign-in is temporarily unavailable"): the
     * throwable is logged once, correlated by trace.id, and never returned.
     *
     * @param ctx Routing context
     * @param status HTTP status (5xx)
     * @param error Error code
     * @param message Fixed client-facing message (never exception text)
     * @param cause Failure behind the answer
     */
    public static void sendError(final RoutingContext ctx, final int status,
        final String error, final String message, final Throwable cause) {
        final com.auto1.pantera.http.log.FailureCause failure =
            new com.auto1.pantera.http.log.FailureCause(cause);
        ApiResponse.serverErrorLog(ctx, status, error)
            .field("event.reason", failure.reason())
            .message("API request failed with " + status
                + " (cause: " + failure.reason() + "): " + message)
            .error(cause)
            .log();
        ctx.response()
            .setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(error(status, error, message).encode());
    }

    /**
     * Common fields of the cause record of an API 5xx.
     */
    private static com.auto1.pantera.http.log.EcsLogger serverErrorLog(
        final RoutingContext ctx, final int status, final String error
    ) {
        final com.auto1.pantera.audit.AuditContext audit = new ApiAuditContext(ctx).value();
        final com.auto1.pantera.http.log.EcsLogger log = status == 500
            ? com.auto1.pantera.http.log.EcsLogger.error("com.auto1.pantera.api")
            : com.auto1.pantera.http.log.EcsLogger.warn("com.auto1.pantera.api");
        return log
            .eventCategory("web")
            .eventAction("api_request")
            .eventOutcome("failure")
            .field("error.code", error)
            .field("http.request.method", ctx.request().method().name())
            .field("url.path", ctx.request().path())
            .field("http.response.status_code", status)
            .traceContext(audit.traceId(), audit.clientIp())
            .field("log.source", "application");
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
