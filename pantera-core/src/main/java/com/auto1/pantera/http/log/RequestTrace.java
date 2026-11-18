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
package com.auto1.pantera.http.log;

import com.auto1.pantera.http.Headers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What each repository on the way of one request answered, in the order
 * the answers came: the members a group asked, then the group itself.
 *
 * <p>Only a request that asks for it is traced: the admin troubleshooter
 * replays a client's request with a trace id in {@link #HEADER} and shows
 * the steps, so that "why does this URL answer 404" is read off what
 * happened to the request instead of being guessed from separate probes.
 * {@code ReasonSlice}, which wraps every repository, records a step when the
 * header is there; an ordinary request carries none and costs nothing. A
 * group passes its request headers to its members, so one trace covers the
 * whole walk.</p>
 *
 * @since 2.2.12
 */
public final class RequestTrace {

    /**
     * Internal request header naming the trace of a request. A value sent
     * by a client is dropped at the edge.
     */
    public static final String HEADER = "X-Pantera-Ctx-Trace";

    /**
     * The instance the serving path uses.
     */
    private static final RequestTrace INSTANCE = new RequestTrace();

    /**
     * Source of trace ids.
     */
    private final AtomicLong ids;

    /**
     * Steps per open trace. A trace is removed when it is closed; the bounds
     * only cover one that is never closed.
     */
    private final Cache<String, Queue<Step>> traces;

    /**
     * Ctor.
     */
    public RequestTrace() {
        this.ids = new AtomicLong();
        this.traces = Caffeine.newBuilder()
            .maximumSize(1_000L)
            .expireAfterWrite(Duration.ofMinutes(10L))
            .build();
    }

    /**
     * The instance shared by the serving path.
     * @return Shared instance
     */
    public static RequestTrace instance() {
        return RequestTrace.INSTANCE;
    }

    /**
     * Open a trace.
     * @return Trace id, to be sent in {@link #HEADER} with the request
     */
    public String open() {
        final String trace = Long.toString(this.ids.incrementAndGet(), Character.MAX_RADIX);
        this.traces.put(trace, new ConcurrentLinkedQueue<>());
        return trace;
    }

    /**
     * Whether a request is being traced.
     * @param request Headers of the request
     * @return True if it carries a trace id
     */
    public boolean traced(final Headers request) {
        return request != null && !request.values(RequestTrace.HEADER).isEmpty();
    }

    /**
     * Record what a repository answered. Ignored when the request is not
     * traced or its trace was closed.
     * @param request Headers of the request
     * @param step The repository's answer
     */
    public void step(final Headers request, final Step step) {
        if (request != null) {
            final List<String> trace = request.values(RequestTrace.HEADER);
            if (!trace.isEmpty()) {
                final Queue<Step> steps = this.traces.getIfPresent(trace.get(0));
                if (steps != null) {
                    steps.add(step);
                }
            }
        }
    }

    /**
     * Close a trace.
     * @param trace Trace id
     * @return Its steps, in the order they were recorded
     */
    public List<Step> close(final String trace) {
        final Queue<Step> steps = this.traces.asMap().remove(trace);
        return steps == null ? List.of() : new ArrayList<>(steps);
    }

    /**
     * The answer of one repository to a traced request.
     *
     * @param repository Repository name
     * @param type Repository type, such as {@code maven-proxy}
     * @param status Status answered, 0 when the repository failed without an answer
     * @param reason Why the status is not 2xx (see {@link AnswerReason}), or null
     * @param millis Time the repository took, in milliseconds
     * @since 2.2.12
     */
    public record Step(String repository, String type, int status, String reason, long millis) {
    }
}
