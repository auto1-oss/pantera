/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.api;

import com.auto1.pantera.http.log.EcsLogger;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.ext.web.openapi.RouterBuilder;
import java.io.StringReader;
import javax.json.Json;
import javax.json.JsonObject;

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
                EcsLogger.warn("com.auto1.pantera.api")
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
                .setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(new io.vertx.core.json.JsonObject()
                    .put("code", status)
                    .put("message", msg)
                    .encode());
            EcsLogger.warn("com.auto1.pantera.api")
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
     * Send a JSON error response with standard {code, message} format.
     * @param context Routing context
     * @param status HTTP status code
     * @param message Error message
     */
    protected static void sendError(final RoutingContext context,
        final int status, final String message) {
        context.response()
            .setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(new io.vertx.core.json.JsonObject()
                .put("code", status)
                .put("message", message)
                .encode());
    }

    /**
     * Read body as JsonObject.
     * @param context RoutingContext
     * @return JsonObject
     */
    protected static JsonObject readJsonObject(final RoutingContext context) {
        return Json.createReader(new StringReader(context.body().asString())).readObject();
    }
}
