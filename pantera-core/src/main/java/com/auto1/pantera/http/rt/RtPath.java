/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.rt;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.rq.RequestLine;

/**
 * Route path.
 */
public interface RtPath {
    /**
     * Try respond.
     *
     * @param line    Request line
     * @param headers Headers
     * @param body    Body
     * @return Response if passed routing rule
     */
    Optional<CompletableFuture<Response>> response(RequestLine line, Headers headers, Content body);
}
