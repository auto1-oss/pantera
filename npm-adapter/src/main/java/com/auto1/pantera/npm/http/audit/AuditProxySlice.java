/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.http.audit;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.concurrent.CompletableFuture;

/**
 * Audit proxy slice - forwards audit requests to upstream registry.
 * 
 * This implementation uses a Slice to forward requests to upstream.
 * For full HTTP client support, inject a UriClientSlice configured
 * with the upstream registry URL.
 * 
 * @since 1.1
 */
public final class AuditProxySlice implements Slice {
    
    /**
     * Upstream slice (typically UriClientSlice).
     */
    private final Slice upstream;
    
    /**
     * Constructor.
     * @param upstream Upstream slice (e.g., UriClientSlice to npm registry)
     */
    public AuditProxySlice(final Slice upstream) {
        this.upstream = upstream;
    }
    
    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // Simply forward to upstream slice
        return this.upstream.response(line, headers, body);
    }
}
