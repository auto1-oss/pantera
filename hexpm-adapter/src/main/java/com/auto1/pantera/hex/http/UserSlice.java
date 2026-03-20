/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */

package com.auto1.pantera.hex.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.ResponseBuilder;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * This slice returns content about user in erlang format.
 */
public final class UserSlice implements Slice {
    /**
     * Path to users.
     */
    static final Pattern USERS = Pattern.compile("/users/(?<user>\\S+)");

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        return ResponseBuilder.noContent().completedFuture();
    }
}
