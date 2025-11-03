/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.slice;

import com.artipie.asto.Content;
import com.artipie.asto.Storage;
import com.artipie.asto.memory.InMemoryStorage;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.rq.RqMethod;
import com.artipie.http.headers.Header;
import com.artipie.http.rq.RequestLine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test case for authentication in IndexedBrowsableSlice.
 */
class IndexedBrowsableSliceAuthTest {

    private Storage storage;
    private Slice authSlice;
    private IndexedBrowsableSlice indexedSlice;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        // Create a mock slice that requires authentication
        this.authSlice = new AuthRequiredSlice();
        this.indexedSlice = new IndexedBrowsableSlice(this.authSlice, this.storage, "test-repo");
    }

    @Test
    void shouldRequireAuthenticationForDirectoryListing() throws Exception {
        final RequestLine line = new RequestLine(RqMethod.GET, "/");
        final Headers headers = Headers.from(new Header("Accept", "text/html"));
        final Content body = Content.EMPTY;

        final Response response = this.indexedSlice.response(line, headers, body)
            .get(5, java.util.concurrent.TimeUnit.SECONDS);

        // Should return 401 because authentication failed
        assertEquals(401, response.status().code());
    }

    /**
     * Mock slice that always requires authentication.
     */
    private static class AuthRequiredSlice implements Slice {
        
        @Override
        public CompletableFuture<Response> response(
            final RequestLine line,
            final Headers headers,
            final Content body
        ) {
            // Always return 401 Unauthorized
            return CompletableFuture.completedFuture(
                com.artipie.http.ResponseBuilder.unauthorized()
                    .textBody("Authentication required")
                    .build()
            );
        }
    }
}
