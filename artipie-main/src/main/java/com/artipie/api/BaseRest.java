/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api;

import com.artipie.http.log.EcsLogger;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.ext.web.openapi.router.OpenAPIRoute;
import io.vertx.ext.web.openapi.router.RouterBuilder;
import io.vertx.openapi.validation.RequestParameter;
import io.vertx.openapi.validation.ValidatedRequest;
import java.io.StringReader;
import javax.json.Json;

/**
 * Base class for rest-api operations.
 * @since 0.26
 */
abstract class BaseRest {
    /**
     * Key 'repo' inside json-object.
     */
    protected static final String REPO = "repo";

    /**
     * Mount openapi operation implementations.
     * @param rbr RouterBuilder
     */
    public abstract void init(RouterBuilder rbr);

    /**
     * Handle error.
     * @param code Error code
     * @return Error handler
     */
    protected Handler<RoutingContext> errorHandler(final int code) {
        return context -> {
            final int status;
            if (context.failure() instanceof HttpException) {
                status = ((HttpException) context.failure()).getStatusCode();
            } else {
                status = code;
            }
            // Check if response headers have already been sent
            if (context.response().headWritten()) {
                // Headers already sent, just log the error - can't modify response
                EcsLogger.warn("com.artipie.api")
                    .message("REST API request failed (response already sent)")
                    .eventCategory("api")
                    .eventAction("request_handling")
                    .eventOutcome("failure")
                    .field("http.response.status_code", status)
                    .field("url.path", context.request().path())
                    .field("http.request.method", context.request().method().name())
                    .field("user.name", context.user() != null ? context.user().principal().getString("sub") : null)
                    .error(context.failure())
                    .log();
                // Try to end the response if not already ended
                if (!context.response().ended()) {
                    context.response().end();
                }
                return;
            }
            // Sanitize message - HTTP status messages can't contain control chars
            final String msg = sanitizeStatusMessage(context.failure().getMessage());
            context.response()
                .setStatusMessage(msg)
                .setStatusCode(status)
                .end();
            EcsLogger.warn("com.artipie.api")
                .message("REST API request failed")
                .eventCategory("api")
                .eventAction("request_handling")
                .eventOutcome("failure")
                .field("http.response.status_code", status)
                .field("url.path", context.request().path())
                .field("http.request.method", context.request().method().name())
                .field("user.name", context.user() != null ? context.user().principal().getString("sub") : null)
                .error(context.failure())
                .log();
        };
    }

    /**
     * Sanitize message for use as HTTP status message.
     * HTTP status messages cannot contain control characters like CR/LF.
     * @param message Original message
     * @return Sanitized message safe for HTTP status line
     */
    private static String sanitizeStatusMessage(final String message) {
        if (message == null) {
            return "Error";
        }
        // Replace control characters and limit length
        String sanitized = message
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replaceAll("\\p{Cntrl}", " ");
        // Limit to reasonable length for status message
        if (sanitized.length() > 100) {
            sanitized = sanitized.substring(0, 100) + "...";
        }
        return sanitized;
    }

    /**
     * Read body as javax.json JsonObject (Vert.x 5 compatible).
     * @param context RoutingContext
     * @return JsonObject
     */
    protected static javax.json.JsonObject readJsonObject(final RoutingContext context) {
        final JsonObject body = getBodyAsJson(context);
        if (body == null) {
            throw new IllegalStateException("Request body is null");
        }
        return Json.createReader(new StringReader(body.encode())).readObject();
    }

    /**
     * Get request body as Vert.x JsonObject using Vert.x 5 ValidatedRequest API.
     * @param context RoutingContext
     * @return JsonObject or null if no body
     */
    protected static JsonObject getBodyAsJson(final RoutingContext context) {
        // Try ValidatedRequest first (Vert.x 5 OpenAPI router)
        final ValidatedRequest validatedRequest = context.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
        if (validatedRequest != null) {
            final Object body = validatedRequest.getBody();
            if (body instanceof JsonObject) {
                return (JsonObject) body;
            } else if (body instanceof RequestParameter) {
                // Vert.x 5 OpenAPI router wraps body in RequestParameter
                final RequestParameter param = (RequestParameter) body;
                if (param.isJsonObject()) {
                    return param.getJsonObject();
                } else if (param.isBuffer()) {
                    return param.getBuffer().toJsonObject();
                } else if (param.isString()) {
                    return new JsonObject(param.getString());
                }
            } else if (body instanceof io.vertx.core.buffer.Buffer) {
                return ((io.vertx.core.buffer.Buffer) body).toJsonObject();
            } else if (body instanceof String) {
                return new JsonObject((String) body);
            } else if (body instanceof java.util.Map) {
                return new JsonObject((java.util.Map<String, Object>) body);
            }
        }
        // Try direct body access
        final io.vertx.ext.web.RequestBody requestBody = context.body();
        if (requestBody != null) {
            // Try buffer first - most reliable
            final io.vertx.core.buffer.Buffer buffer = requestBody.buffer();
            if (buffer != null && buffer.length() > 0) {
                try {
                    return buffer.toJsonObject();
                } catch (final Exception e) {
                    // Not JSON, continue trying other methods
                }
            }
        }
        return null;
    }
}
