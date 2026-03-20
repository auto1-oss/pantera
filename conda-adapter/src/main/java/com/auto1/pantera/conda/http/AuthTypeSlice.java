/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.conda.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.ResponseBuilder;

import javax.json.Json;
import java.util.concurrent.CompletableFuture;

/**
 * Slice to serve on `/authentication-type`, returns stab json body.
 */
final class AuthTypeSlice implements Slice {
    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        return ResponseBuilder.ok()
            .jsonBody(Json.createObjectBuilder().add("authentication_type", "password").build())
            .completedFuture();
    }
}
