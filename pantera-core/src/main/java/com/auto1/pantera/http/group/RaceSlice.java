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
package com.auto1.pantera.http.group;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.log.EcsLogger;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Races multiple slices in parallel and returns the first 200 response.
 *
 * <p>This is a low-level utility for "first response wins" patterns —
 * NOT a group repository resolver. For group/virtual repository resolution
 * with index lookup, member flattening, and negative caching, see
 * {@link com.auto1.pantera.group.GroupSlice} in pantera-main.
 */
public final class RaceSlice implements Slice {

    /**
     * Target slices.
     */
    private final List<Slice> targets;

    /**
     * New race slice.
     * @param targets Slices to race
     */
    public RaceSlice(final Slice... targets) {
        this(Arrays.asList(targets));
    }

    /**
     * New race slice.
     * @param targets Slices to race
     */
    public RaceSlice(final List<Slice> targets) {
        this.targets = Collections.unmodifiableList(targets);
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body
    ) {
        // Parallel race strategy:
        // try all repositories simultaneously and return the first successful
        // response (non-404). This reduces latency when the artifact is only
        // available in later repositories.

        if (this.targets.isEmpty()) {
            // Consume request body to prevent Vert.x request leak
            return body.asBytesFuture().thenApply(ignored ->
                ResponseBuilder.notFound().build()
            );
        }

        // Consume the original request body once and create fresh
        // {@link Content} instances for each member. This is required for
        // POST requests (such as npm audit), where the body must be forwarded
        // to all members. For GET/HEAD requests the body is typically empty,
        // so the additional buffering is negligible.
        return body.asBytesFuture().thenCompose(requestBytes -> {
            // Create a result future
            final CompletableFuture<Response> result = new CompletableFuture<>();

            // Track how many repos have responded with 404/error
            final java.util.concurrent.atomic.AtomicInteger failedCount =
                new java.util.concurrent.atomic.AtomicInteger(0);

            // Start all repository requests in parallel
            for (int i = 0; i < this.targets.size(); i++) {
                final int index = i;
                final Slice target = this.targets.get(i);

                // Create a fresh Content instance for each member from buffered bytes
                final Content memberBody = requestBytes.length == 0
                    ? Content.EMPTY
                    : new Content.From(requestBytes);

                EcsLogger.debug("com.auto1.pantera.http")
                    .message("Sending request to target (index: " + index + ")")
                    .eventCategory("web")
                    .eventAction("pending")
                    .eventOutcome("unknown")
                    .field("url.path", line.uri().getPath())
                    .log();
                target.response(line, headers, memberBody)
                .thenCompose(res -> {
                    // If result already completed (someone else won), consume and discard
                    if (result.isDone()) {
                        EcsLogger.debug("com.auto1.pantera.http")
                            .message("Repository response arrived after race completed (index: " + index + ")")
                            .eventCategory("web")
                            .eventAction("group_race")
                            .eventOutcome("success")
                            .field("event.reason", "late_response")
                            .log();
                        // Consume body even if this response lost the race to
                        // avoid leaking underlying HTTP resources.
                        return res.body().asBytesFuture().thenApply(ignored -> null);
                    }

                    if (res.status() == RsStatus.NOT_FOUND) {
                        EcsLogger.debug("com.auto1.pantera.http")
                            .message("Repository returned 404 (index: " + index + ")")
                            .eventCategory("web")
                            .eventAction("group_race")
                            .eventOutcome("failure")
                            .field("event.reason", "artifact_not_found")
                            .log();
                        // Consume 404 response bodies as well to avoid leaks.
                        return res.body().asBytesFuture().thenApply(ignored -> {
                            if (failedCount.incrementAndGet() == this.targets.size()) {
                                // All repos returned 404, return 404
                                result.complete(ResponseBuilder.notFound().build());
                            }
                            return null;
                        });
                    }

                    // SUCCESS! This repo has the artifact
                    // Complete the result (first success wins) - don't consume body, it will be served
                    EcsLogger.debug("com.auto1.pantera.http")
                        .message("Repository found artifact (index: " + index + ")")
                        .eventCategory("web")
                        .eventAction("group_race")
                        .eventOutcome("success")
                        .field("http.response.status_code", res.status().code())
                        .log();
                    result.complete(res);
                    return CompletableFuture.completedFuture(null);
                })
                .exceptionally(err -> {
                    if (result.isDone()) {
                        return null;
                    }
                    EcsLogger.warn("com.auto1.pantera.http")
                        .message("Failed to get response from repository (index: " + index + ")")
                        .eventCategory("web")
                        .eventAction("group_race")
                        .eventOutcome("failure")
                        .error(err)
                        .log();
                    // Count this as a failure
                    if (failedCount.incrementAndGet() == this.targets.size()) {
                        // All repos failed, return 404
                        result.complete(ResponseBuilder.notFound().build());
                    }
                    return null;
                });
            }

            return result;
        });
    }
}
