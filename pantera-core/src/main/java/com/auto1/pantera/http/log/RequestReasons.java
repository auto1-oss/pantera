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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the code handling a request found out about why it will not answer
 * 2xx, kept until the answer is built.
 *
 * <p>The place that knows the cause (a negative-cache hit, the upstream's
 * 404, a lost connection, a full bulkhead) is rarely the place that builds
 * the answer, and never the place that writes the access record. It notes
 * the cause here, under the slot its request carries in
 * {@link #SLOT_HEADER}; {@code ReasonSlice}, which opened the slot around
 * one repository's handling of the request, closes it when that repository
 * has answered and puts the reason on the answer (see {@link AnswerReason}).
 * A slot belongs to one request in one repository, so the members of a group
 * and concurrent requests never see each other's reasons.</p>
 *
 * <p>Several causes can be noted for one slot: the remotes of a proxy, the
 * members of a group. The one that explains most is kept (see
 * {@link ReasonWeight}); of two that explain as much, the first.</p>
 *
 * @since 2.2.12
 */
public final class RequestReasons {

    /**
     * Internal request header naming the slot of a request. Set by
     * {@code ReasonSlice}; a value sent by a client is dropped.
     */
    public static final String SLOT_HEADER = "X-Pantera-Ctx-Reason-Slot";

    /**
     * Reason: answered from the negative cache, the upstream was not asked.
     */
    public static final String NEGATIVE_CACHE = "negative_cache";

    /**
     * The instance the serving path uses.
     */
    private static final RequestReasons INSTANCE = new RequestReasons();

    /**
     * Source of slot ids.
     */
    private final AtomicLong ids;

    /**
     * Best reason noted per open slot. A slot is removed when it is closed;
     * the bounds only cover a slot that is never closed.
     */
    private final Cache<String, String> slots;

    /**
     * Ctor.
     */
    public RequestReasons() {
        this.ids = new AtomicLong();
        this.slots = Caffeine.newBuilder()
            .maximumSize(100_000L)
            .expireAfterWrite(Duration.ofMinutes(10L))
            .build();
    }

    /**
     * The instance shared by the serving path.
     * @return Shared instance
     */
    public static RequestReasons instance() {
        return RequestReasons.INSTANCE;
    }

    /**
     * Reason: the upstream answered this status.
     * @param code Status the upstream answered
     * @return Reason token, {@code upstream_status_<code>}
     */
    public static String upstream(final int code) {
        return "upstream_status_" + code;
    }

    /**
     * Open a slot.
     * @return Slot id, to be sent down in {@link #SLOT_HEADER}
     */
    public String open() {
        return Long.toString(this.ids.incrementAndGet(), Character.MAX_RADIX);
    }

    /**
     * Note why the request will not be answered 2xx. Ignored when the
     * request carries no slot.
     * @param request Headers of the request being handled
     * @param reason Reason token
     */
    public void note(final Headers request, final String reason) {
        if (request != null && reason != null && !reason.isBlank()) {
            final List<String> slot = request.values(RequestReasons.SLOT_HEADER);
            if (!slot.isEmpty()) {
                this.slots.asMap().merge(
                    slot.get(0), reason,
                    (held, added) ->
                        new ReasonWeight(added).value() > new ReasonWeight(held).value()
                            ? added : held
                );
            }
        }
    }

    /**
     * Close a slot.
     * @param slot Slot id
     * @return The reason that explains most among those noted, if any
     */
    public Optional<String> close(final String slot) {
        return Optional.ofNullable(this.slots.asMap().remove(slot));
    }
}
