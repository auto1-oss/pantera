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
 * {@link com.auto1.pantera.group.GroupResolver} in pantera-main.
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

            // Track how many remotes have completed (any outcome).
            final java.util.concurrent.atomic.AtomicInteger failedCount =
                new java.util.concurrent.atomic.AtomicInteger(0);

            // Track whether ANY remote returned a 5xx or threw — informs the
            // 502-vs-404 decision when no 2xx and no 403 was observed.
            final java.util.concurrent.atomic.AtomicBoolean anyServerError =
                new java.util.concurrent.atomic.AtomicBoolean(false);

            // First 403 captured (status, headers, drained bytes) so we can
            // forward it verbatim if the priority rules pick "forbidden" as
            // the final answer. Saved drained-form to avoid leaking the
            // underlying HTTP body — every per-target handler drains its
            // body, only the SAVED 403 is reconstituted at completion.
            final java.util.concurrent.atomic.AtomicReference<DrainedResponse> firstForbidden =
                new java.util.concurrent.atomic.AtomicReference<>();

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
                        return res.body().asBytesFuture().thenApply(ignored -> null);
                    }

                    final int code = res.status().code();

                    // Priority rule 1: 2xx / 3xx wins immediately. Don't drain
                    // the body — it will be served to the client. Drain any
                    // saved 403 since it lost.
                    if (code >= 200 && code < 400) {
                        EcsLogger.debug("com.auto1.pantera.http")
                            .message("Repository found artifact (index: " + index + ")")
                            .eventCategory("web")
                            .eventAction("group_race")
                            .eventOutcome("success")
                            .field("http.response.status_code", code)
                            .log();
                        result.complete(res);
                        final DrainedResponse savedForbidden = firstForbidden.getAndSet(null);
                        if (savedForbidden == null) {
                            return CompletableFuture.completedFuture(null);
                        }
                        // No body to drain — DrainedResponse already has bytes,
                        // and the underlying HTTP body was drained at capture
                        // time. Nothing further to release.
                        return CompletableFuture.completedFuture(null);
                    }

                    // Failure paths: drain body, classify, race-continue.
                    final boolean isForbidden = code == RsStatus.FORBIDDEN.code();
                    final boolean is5xx = code >= 500;
                    if (is5xx) {
                        anyServerError.set(true);
                    }
                    EcsLogger.debug("com.auto1.pantera.http")
                        .message("Repository returned " + code
                            + " (index: " + index + "); race continues")
                        .eventCategory("web")
                        .eventAction("group_race")
                        .eventOutcome("failure")
                        .field("event.reason",
                            code == RsStatus.NOT_FOUND.code() ? "artifact_not_found"
                                : isForbidden ? "forbidden"
                                : is5xx ? "upstream_error"
                                : "client_error")
                        .field("http.response.status_code", code)
                        .log();
                    return res.body().asBytesFuture().thenApply(bytes -> {
                        if (isForbidden) {
                            // CAS — only the FIRST 403 wins; later 403s' bytes
                            // are dropped after the drain above (already done).
                            firstForbidden.compareAndSet(
                                null,
                                new DrainedResponse(res.status(), res.headers(), bytes)
                            );
                        }
                        if (failedCount.incrementAndGet() == this.targets.size()) {
                            completeBasedOnPriority(result, firstForbidden, anyServerError);
                        }
                        return null;
                    });
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
                    // Treat exceptions the same as 5xx — race-continue + remember.
                    anyServerError.set(true);
                    if (failedCount.incrementAndGet() == this.targets.size()) {
                        completeBasedOnPriority(result, firstForbidden, anyServerError);
                    }
                    return null;
                });
            }

            return result;
        });
    }

    /**
     * Final-decision logic when all targets have responded with non-success.
     * Priority order, applied in sequence:
     * <ol>
     *   <li>If any target returned 403 — forward the FIRST 403 captured (its
     *       drained body, headers, status). 403 says "exists but blocked";
     *       outranks 404 (definitively absent) and 5xx (transient).</li>
     *   <li>If any target returned 5xx (or threw) — return 502, since at
     *       least one upstream's true state is unknown.</li>
     *   <li>Otherwise (all 404 / similar definitive misses) — return 404.</li>
     * </ol>
     */
    private static void completeBasedOnPriority(
        final CompletableFuture<Response> result,
        final java.util.concurrent.atomic.AtomicReference<DrainedResponse> firstForbidden,
        final java.util.concurrent.atomic.AtomicBoolean anyServerError
    ) {
        final DrainedResponse forbidden = firstForbidden.get();
        if (forbidden != null) {
            result.complete(new Response(
                forbidden.status, forbidden.headers, new Content.From(forbidden.bytes)
            ));
            return;
        }
        if (anyServerError.get()) {
            result.complete(
                ResponseBuilder.badGateway()
                    .textBody("All upstream remotes failed")
                    .build()
            );
            return;
        }
        result.complete(ResponseBuilder.notFound().build());
    }

    /**
     * Captured 403 response — status, headers, and fully-drained body bytes.
     * Held in an AtomicReference so the FIRST 403 wins via CAS; subsequent
     * 403s are dropped after their bodies are drained at the per-target
     * handler. If the priority rule selects "forbidden" as the final answer,
     * we reconstitute a Response from these fields.
     */
    private static final class DrainedResponse {
        final RsStatus status;
        final Headers headers;
        final byte[] bytes;

        DrainedResponse(final RsStatus status, final Headers headers, final byte[] bytes) {
            this.status = status;
            this.headers = headers;
            this.bytes = bytes;
        }
    }
}
