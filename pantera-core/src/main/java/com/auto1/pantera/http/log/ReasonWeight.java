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

import java.util.Set;

/**
 * How much a reason explains about a non-2xx answer.
 *
 * <p>Several causes can stand behind one answer: the remotes of a proxy, the
 * members of a group. The one that explains most is reported: a failure
 * (a lost connection, a timeout, an upstream 5xx), then a request Pantera
 * refused to forward (open circuit breaker, full bulkhead), then a cooldown
 * block, then a cached "not found", then an upstream 4xx, then the upstream's
 * own 404. A reason that only names the status (see {@link StatusReason})
 * explains nothing and weighs least.</p>
 *
 * @since 2.2.12
 */
public final class ReasonWeight {

    /**
     * Requests Pantera did not forward: the upstream was not asked.
     */
    private static final Set<String> REFUSALS = Set.of("circuit_open", "bulkhead_full");

    /**
     * Prefix of the reason "the upstream answered this status".
     */
    private static final String UPSTREAM = "upstream_status_";

    /**
     * Reason token.
     */
    private final String reason;

    /**
     * Ctor.
     * @param reason Reason token
     */
    public ReasonWeight(final String reason) {
        this.reason = reason;
    }

    /**
     * Weight of the reason.
     * @return Weight, higher explains more
     */
    public int value() {
        final int weight;
        if (StatusReason.TOKENS.contains(this.reason)) {
            weight = 0;
        } else if (this.reason.startsWith(ReasonWeight.UPSTREAM)) {
            weight = ReasonWeight.upstream(this.reason.substring(ReasonWeight.UPSTREAM.length()));
        } else if ("upstream_no_files".equals(this.reason)) {
            // The upstream answered, with a project page that lists no file:
            // as much a "not found" as its own 404.
            weight = 10;
        } else if ("skipped_by_routing_rule".equals(this.reason)) {
            // A member that was not asked explains a "not found" less than
            // any member that was: it only says where nobody looked.
            weight = 5;
        } else if ("negative_cache".equals(this.reason)) {
            weight = 30;
        } else if ("cooldown_active".equals(this.reason)) {
            weight = 40;
        } else if (ReasonWeight.REFUSALS.contains(this.reason)) {
            weight = 50;
        } else {
            weight = 60;
        }
        return weight;
    }

    /**
     * Weight of an upstream answer: a 404 is the upstream saying "not
     * found", another 4xx is the upstream refusing the request, anything
     * else is the upstream failing.
     * @param code Status code as text
     * @return Weight
     */
    private static int upstream(final String code) {
        final int weight;
        if ("404".equals(code) || "410".equals(code)) {
            weight = 10;
        } else if (code.length() == 3 && code.charAt(0) == '4') {
            weight = 20;
        } else {
            weight = 60;
        }
        return weight;
    }
}
