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
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.json.Json;

/**
 * NPM whoami slice that extracts username from validated JWT.
 * Requires authentication via CombinedAuthzSliceWrap which sets artipie_login header
 * after successful JWT validation.
 * 
 * @since 1.2
 */
public final class JwtWhoAmISlice implements Slice {
    
    private static final Logger LOGGER = Logger.getLogger(JwtWhoAmISlice.class.getName());
    
    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // Extract authenticated username from context header set by auth slices
        // The CombinedAuthzSliceWrap/BearerAuthzSlice adds "artipie_login" header 
        // after JWT validation
        final String username = new RqHeaders(headers, "artipie_login").stream()
            .findFirst()
            .orElse(null);
        
        if (username == null || username.isEmpty()) {
            LOGGER.log(Level.WARNING, "NPM whoami called without authentication");
            return CompletableFuture.completedFuture(
                ResponseBuilder.unauthorized()
                    .jsonBody("{\"error\": \"Authentication required\"}")
                    .build()
            );
        }
        
        LOGGER.log(Level.INFO, "NPM whoami for user: {0}", username);
        
        // Return username in npm whoami format
        return CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .jsonBody(
                    Json.createObjectBuilder()
                        .add("username", username)
                        .build()
                        .toString()
                )
                .build()
        );
    }
}
