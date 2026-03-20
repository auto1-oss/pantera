/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.http.auth;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;
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
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenApply(ignored -> {
            // Extract authenticated username from context header set by auth slices
            // The BearerAuthzSlice/CombinedAuthzSliceWrap adds "pantera_login" header
            final String username = new RqHeaders(headers, "pantera_login").stream()
                .findFirst()
                .orElse(null);

            if (username == null || username.isEmpty()) {
                return ResponseBuilder.unauthorized()
                    .jsonBody(Json.createObjectBuilder()
                        .add("error", "Not authenticated")
                        .build())
                    .build();
            }

            return ResponseBuilder.ok()
                .jsonBody(Json.createObjectBuilder()
                    .add("username", username)
                    .build())
                .build();
        });
    }
}
