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
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.LogSanitizer;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Slice that logs incoming requests and outgoing responses.
 */
public final class LoggingSlice implements Slice {

    /**
     * Logging level.
     */
    private final Level level;

    /**
     * Delegate slice.
     */
    private final Slice slice;

    /**
     * @param slice Slice.
     */
    public LoggingSlice(final Slice slice) {
        this(Level.FINE, slice);
    }

    /**
     * @param level Logging level.
     * @param slice Slice.
     */
    public LoggingSlice(final Level level, final Slice slice) {
        this.level = level;
        this.slice = slice;
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body
    ) {
        final StringBuilder msg = new StringBuilder(">> ").append(line);
        // Sanitize headers to prevent credential leakage in logs
        LoggingSlice.append(msg, LogSanitizer.sanitizeHeaders(headers));

        // Log request at DEBUG level (diagnostic only)
        if (this.level.intValue() <= Level.FINE.intValue()) {
            EcsLogger.debug("com.auto1.pantera.http")
                .message("HTTP request: " + msg.toString())
                .eventCategory("web")
                .eventAction("request")
                .log();
        }

        return slice.response(line, headers, body)
            .thenApply(res -> {
                final StringBuilder sb = new StringBuilder("<< ").append(res.status());
                // Sanitize response headers as well
                LoggingSlice.append(sb, LogSanitizer.sanitizeHeaders(res.headers()));

                // Log response at DEBUG level (diagnostic only)
                if (LoggingSlice.this.level.intValue() <= Level.FINE.intValue()) {
                    EcsLogger.debug("com.auto1.pantera.http")
                        .message("HTTP response: " + sb.toString())
                        .eventCategory("web")
                        .eventAction("response")
                        .log();
                }

                return res;
            });
    }

    /**
     * Append headers to {@link StringBuilder}.
     *
     * @param builder Target {@link StringBuilder}.
     * @param headers Headers to be appended.
     */
    private static void append(StringBuilder builder, Headers headers) {
        for (Header header : headers) {
            builder.append('\n').append(header.getKey()).append(": ").append(header.getValue());
        }
    }
}
