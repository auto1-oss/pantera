/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.http.auth;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqHeaders;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;

/**
 * WhoAmI slice - returns current authenticated user.
 * Endpoint: GET /-/whoami
 *
 * @since 1.1
 */
public final class WhoAmISlice implements Slice {
    
    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // Extract authenticated username from context header set by auth slices
        // The BearerAuthzSlice/CombinedAuthzSliceWrap adds "artipie_login" header
        final String username = new RqHeaders(headers, "artipie_login").stream()
            .findFirst()
            .orElse(null);
            
        if (username == null || username.isEmpty()) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.unauthorized()
                    .jsonBody(Json.createObjectBuilder()
                        .add("error", "Not authenticated")
                        .build())
                    .build()
            );
        }
        
        return CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .jsonBody(Json.createObjectBuilder()
                    .add("username", username)
                    .build())
                .build()
        );
    }
}
