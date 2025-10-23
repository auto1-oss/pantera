/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.group;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.RsStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Standard group {@link Slice} implementation.
 */
public final class GroupSlice implements Slice {

    private static final Logger LOGGER = LoggerFactory.getLogger(GroupSlice.class);

    /**
     * Target slices.
     */
    private final List<Slice> targets;

    /**
     * New group slice.
     * @param targets Slices to group
     */
    public GroupSlice(final Slice... targets) {
        this(Arrays.asList(targets));
    }

    /**
     * New group slice.
     * @param targets Slices to group
     */
    public GroupSlice(final List<Slice> targets) {
        this.targets = Collections.unmodifiableList(targets);
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body
    ) {
        // Try repositories in order, but asynchronously (non-blocking)
        // This is crucial for group repositories with proxies (avoid blocking on remote calls)
        return this.tryNext(0, line, headers, body);
    }

    /**
     * Try the next repository in the list asynchronously.
     * @param index Current repository index
     * @param line Request line
     * @param headers Request headers
     * @param body Request body
     * @return Response future
     */
    private CompletableFuture<Response> tryNext(
        final int index,
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        if (index >= this.targets.size()) {
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        }

        final Slice current = this.targets.get(index);
        return current.response(line, headers, body)
            .handle((res, err) -> {
                if (err != null) {
                    LOGGER.warn("Failed to get response from repository at index " + index, err);
                    // On error, try next repository
                    return this.tryNext(index + 1, line, headers, body);
                }
                if (res.status() != RsStatus.NOT_FOUND) {
                    // Found it, return immediately
                    return CompletableFuture.completedFuture(res);
                }
                // Not found, try next repository
                return this.tryNext(index + 1, line, headers, body);
            })
            .thenCompose(future -> future);
    }
}
