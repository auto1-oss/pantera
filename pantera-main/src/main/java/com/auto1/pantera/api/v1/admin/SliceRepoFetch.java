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
package com.auto1.pantera.api.v1.admin;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import java.net.URI;
import java.util.AbstractMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.MDC;

/**
 * {@link RepoFetch} over the live repository slices.
 *
 * <p>The request enters the repository's slice exactly where
 * {@code SliceByPath} would hand a client request to it — BELOW the
 * client-facing {@code EcsLoggingSlice}, so no internal header is ever
 * accepted from outside: only the caller's own {@code Authorization} and an
 * {@code Accept} value are sent, and the repository's authentication and
 * authorization decide the answer as they would for that caller. The
 * request context (trace id, client ip) rides on the internal context
 * headers so logs and audit records correlate with the admin API call.</p>
 *
 * @since 2.2.9
 */
public final class SliceRepoFetch implements RepoFetch {

    /**
     * Hang guard for one in-process request.
     */
    private static final long TIMEOUT_SECONDS = 60L;

    /**
     * Repository slice by name.
     */
    private final Function<String, Slice> slices;

    /**
     * Ctor.
     *
     * @param slices Repository slice by name (the same resolution the
     *  serving path uses)
     */
    public SliceRepoFetch(final Function<String, Slice> slices) {
        this.slices = slices;
    }

    @Override
    public CompletableFuture<Fetched> get(
        final String repo, final String path, final String authorization,
        final String accept, final int maxBody
    ) {
        final String rel = path.startsWith("/") ? path : "/" + path;
        final Headers headers = new Headers();
        if (authorization != null && !authorization.isBlank()) {
            headers.add(new Header("Authorization", authorization));
        }
        if (accept != null && !accept.isBlank()) {
            headers.add(new Header("Accept", accept));
        }
        final String trace = MDC.get(EcsMdc.TRACE_ID);
        if (trace != null && !trace.isBlank()) {
            headers.add(new Header(EcsLoggingSlice.CTX_TRACE_ID_HEADER, trace));
        }
        final String client = MDC.get(EcsMdc.CLIENT_IP);
        if (client != null && !client.isBlank()) {
            headers.add(new Header(EcsLoggingSlice.CTX_CLIENT_IP_HEADER, client));
        }
        final RequestLine line = new RequestLine(
            RqMethod.GET, URI.create("/" + repo + rel), "HTTP/1.1"
        );
        EcsLogger.info("com.auto1.pantera.api.v1.admin")
            .message("Admin diagnostic in-process request")
            .eventCategory("web")
            .eventAction("admin_inprocess_fetch")
            .field("repository.name", repo)
            .field("url.path", line.uri().getPath())
            .field("log.source", "application")
            .log();
        return this.slices.apply(repo)
            .response(line, headers, Content.EMPTY)
            .thenCompose(resp -> new BoundedBody(maxBody).read(resp.body())
                .thenApply(read -> new Fetched(
                    resp.status().code(),
                    resp.headers().stream()
                        .map(hdr -> new AbstractMap.SimpleImmutableEntry<>(
                            hdr.getKey(), hdr.getValue()
                        ))
                        .collect(Collectors.<Map.Entry<String, String>>toList()),
                    read.bytes(), read.truncated(), read.total()
                )))
            .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
