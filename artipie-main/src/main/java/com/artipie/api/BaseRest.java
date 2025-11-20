/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api;

import com.artipie.http.log.EcsLogger;
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
            if (context.failure() instanceof HttpException) {
                context.response()
                    .setStatusMessage(context.failure().getMessage())
                    .setStatusCode(((HttpException) context.failure()).getStatusCode())
                    .end();
            } else {
                context.response()
                    .setStatusMessage(context.failure().getMessage())
                    .setStatusCode(code)
                    .end();
            }
            EcsLogger.error("com.artipie.api")
                .message("REST API request failed")
                .eventCategory("api")
                .eventAction("request_handling")
                .eventOutcome("failure")
                .field("http.response.status_code", code)
                .error(context.failure())
                .log();
        };
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
