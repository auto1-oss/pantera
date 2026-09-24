/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.LogSanitizer;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.concurrent.CompletableFuture;

/**
 * Rejects a request whose decoded path contains a {@code ..} segment with
 * {@code 400 Bad Request} before any repository code runs.
 *
 * <p>No artifact format uses a parent segment in a request path. Storage
 * already contains every key within its root, but a traversal attempt used to
 * surface there as an out-of-storage {@code IOException}, which each adapter
 * answered with a 500 and one or more ERROR stack traces: any client could
 * inflate 5xx metrics and error logs at will. The check runs on the decoded
 * path, so percent-encoded {@code %2e%2e} is caught too.</p>
 *
 * <p>A decoded path carrying a control character (C0 range or DEL, e.g. an
 * encoded NUL or CR/LF) is rejected with 400 as well. No artifact format
 * names a file with one; NUL cannot be stored on a filesystem or in a
 * PostgreSQL text column, and CR/LF broke the routing regexes, so such a
 * request used to end in a 500 or a misleading 404 deeper in the stack.</p>
 *
 * @since 2.2.9
 */
public final class PathTraversalGuardSlice implements Slice {

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Ctor.
     * @param origin Origin slice
     */
    public PathTraversalGuardSlice(final Slice origin) {
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final String path = line.uri().getPath();
        final CompletableFuture<Response> res;
        if (path != null && PathTraversalGuardSlice.hasParentSegment(path)) {
            EcsLogger.warn("com.auto1.pantera.http")
                .message("Rejected request path with a parent ('..') segment")
                .eventCategory("web")
                .eventAction("path_traversal_reject")
                .eventOutcome("failure")
                .field("http.request.method", line.method().value())
                .field("url.path", LogSanitizer.sanitizeUrl(line.uri().getRawPath()))
                .field("http.response.status_code", 400)
                .field("log.source", "application")
                .log();
            res = body.discard().thenApply(
                ignored -> ResponseBuilder.badRequest()
                    .textBody("Bad Request: path must not contain '..' segments")
                    .build()
            );
        } else if (path != null && PathTraversalGuardSlice.hasControlChar(path)) {
            EcsLogger.warn("com.auto1.pantera.http")
                .message("Rejected request path with a control character")
                .eventCategory("web")
                .eventAction("path_control_char_reject")
                .eventOutcome("failure")
                .field("http.request.method", line.method().value())
                .field("url.path", LogSanitizer.sanitizeUrl(line.uri().getRawPath()))
                .field("http.response.status_code", 400)
                .field("log.source", "application")
                .log();
            res = body.discard().thenApply(
                ignored -> ResponseBuilder.badRequest()
                    .textBody("Bad Request: path must not contain control characters")
                    .build()
            );
        } else {
            res = this.origin.response(line, headers, body);
        }
        return res;
    }

    /**
     * Whether a decoded path contains a C0 control character or DEL.
     * @param path Decoded request path
     * @return True if any character is below U+0020 or is U+007F
     */
    private static boolean hasControlChar(final String path) {
        boolean found = false;
        for (int idx = 0; idx < path.length(); idx += 1) {
            final char chr = path.charAt(idx);
            if (chr < ' ' || chr == 0x7f) {
                found = true;
                break;
            }
        }
        return found;
    }

    /**
     * Whether a decoded path has a segment that is exactly {@code ..}.
     * @param path Decoded request path
     * @return True if any segment is {@code ..}
     */
    private static boolean hasParentSegment(final String path) {
        boolean found = false;
        for (final String segment : path.split("/", -1)) {
            if ("..".equals(segment)) {
                found = true;
                break;
            }
        }
        return found;
    }
}
