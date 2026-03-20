/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.nuget.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;

import java.util.concurrent.CompletableFuture;

/**
 * Resource serving HTTP requests.
 */
public interface Resource {
    /**
     * Serve GET method.
     *
     * @param headers Request headers.
     * @return Response to request.
     */
    CompletableFuture<Response> get(Headers headers);

    /**
     * Serve PUT method.
     *
     * @param headers Request headers.
     * @param body    Request body.
     * @return Response to request.
     */
    CompletableFuture<Response> put(Headers headers, Content body);
}
