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

import java.util.Set;

/**
 * The reason of a repository's answer (the {@code event.reason} token of its
 * access record) in words an operator can act on: what happened, and what
 * to do about it.
 *
 * @since 2.2.12
 */
final class ReasonText {

    /**
     * Prefix of the reason "the upstream answered this status".
     */
    private static final String UPSTREAM = "upstream_status_";

    /**
     * Reasons that are a timeout of the upstream call.
     */
    private static final Set<String> TIMEOUTS =
        Set.of("read_timeout", "idle_timeout", "request_timeout");

    /**
     * Reasons that are a failure of the upstream call or of the upstream.
     */
    private static final Set<String> FAILURES = Set.of(
        "connection_refused", "connection_closed", "connect_timeout", "read_timeout",
        "idle_timeout", "request_timeout", "unknown_host", "tls_error", "request_rejected"
    );

    /**
     * Reason token, or null when the answer carries none.
     */
    private final String reason;

    /**
     * Status the repository answered, 0 when it failed without an answer.
     */
    private final int status;

    /**
     * Ctor.
     *
     * @param reason Reason token, may be null
     * @param status Status answered
     */
    ReasonText(final String reason, final int status) {
        this.reason = reason;
        this.status = status;
    }

    /**
     * Whether the upstream, or the call to it, failed.
     *
     * @return True for a transport failure or an upstream 5xx
     */
    boolean upstreamFailed() {
        return this.reason != null && (ReasonText.FAILURES.contains(this.reason)
            || this.reason.startsWith(ReasonText.UPSTREAM)
            && this.reason.charAt(ReasonText.UPSTREAM.length()) == '5');
    }

    /**
     * Whether this is a plain "not found": the path is in no storage and
     * the upstream, if asked, has no such file either.
     *
     * @return True when nothing was cached wrongly and nothing failed
     */
    boolean plainNotFound() {
        return "not_found".equals(this.reason) || "upstream_status_404".equals(this.reason)
            || "upstream_status_410".equals(this.reason);
    }

    /**
     * What happened in the repository.
     *
     * @return Sentence fragment that follows the repository's name
     */
    String explanation() {
        final String text;
        if (this.reason == null) {
            text = this.status >= 200 && this.status < 400
                ? "served the request" : "gave no reason for this answer";
        } else if (this.reason.startsWith(ReasonText.UPSTREAM)) {
            text = ReasonText.upstream(this.reason.substring(ReasonText.UPSTREAM.length()));
        } else if (ReasonText.TIMEOUTS.contains(this.reason)) {
            text = "its upstream did not answer in time";
        } else {
            text = ReasonText.other(this.reason);
        }
        return text;
    }

    /**
     * What to do about it.
     *
     * @return Advice, empty when there is nothing to do
     */
    String advice() {
        final String text;
        if (this.reason == null) {
            text = "";
        } else if (this.plainNotFound()) {
            text = "Nothing is wrong in Pantera. Check the coordinates, and whether the "
                + "artifact is published in a repository this one does not include.";
        } else if (this.upstreamFailed()) {
            text = "The upstream is the problem, not the artifact. Check the upstream "
                + "directly and the upstream breaker below.";
        } else {
            text = ReasonText.todo(this.reason);
        }
        return text;
    }

    /**
     * Explanation of an upstream answer.
     *
     * @param code Status the upstream answered, as text
     * @return Sentence fragment
     */
    private static String upstream(final String code) {
        final String text;
        if ("404".equals(code) || "410".equals(code)) {
            text = "its upstream answered " + code + ": the upstream does not have this file";
        } else if ("401".equals(code) || "403".equals(code)) {
            text = "its upstream answered " + code
                + ": the upstream refuses the credentials Pantera sends, or this path";
        } else if ("429".equals(code)) {
            text = "its upstream answered 429: the upstream is rate limiting Pantera";
        } else if (code.startsWith("4")) {
            text = "its upstream answered " + code + ": the upstream refuses this request";
        } else {
            text = "its upstream answered " + code + ": the upstream is failing";
        }
        return text;
    }

    /**
     * Explanation of a reason that is neither an upstream answer nor a
     * timeout.
     *
     * @param reason Reason token
     * @return Sentence fragment
     */
    private static String other(final String reason) {
        final String text;
        switch (reason) {
            case "negative_cache" -> text = "answered from its negative cache: an earlier "
                + "\"not found\" is still remembered, and the upstream was not asked";
            case "not_found" -> text = "does not have this path";
            case "skipped_by_routing_rule" ->
                text = "was not asked: a routing rule of the group limits it to other paths";
            case "connection_refused" -> text = "its upstream refused the connection";
            case "connection_closed" ->
                text = "its upstream closed the connection before answering";
            case "connect_timeout" -> text = "connecting to its upstream timed out";
            case "unknown_host" -> text = "the host name of its upstream does not resolve";
            case "tls_error" -> text = "the TLS handshake with its upstream failed";
            case "request_rejected" ->
                text = "the HTTP client's queue of requests to its upstream is full";
            case "egress_denied" -> text = "its upstream URL is not allowed by the egress policy";
            case "bulkhead_full" -> text = "had no free permit within the wait bound: it is "
                + "running as many requests as it is allowed to";
            case "circuit_open" -> text = "did not ask its upstream: the circuit breaker is "
                + "open after repeated failures";
            case "cooldown_active" -> text = "blocks this version by cooldown";
            case "authentication_required" -> text = "was sent no valid credentials";
            case "forbidden" -> text = "refuses this account: no permission to read it";
            default -> text = "answered so because of: " + reason.replace('_', ' ');
        }
        return text;
    }

    /**
     * Advice for a reason that is neither a plain "not found" nor an
     * upstream failure.
     *
     * @param reason Reason token
     * @return Advice, empty when there is nothing to do
     */
    private static String todo(final String reason) {
        final String text;
        switch (reason) {
            case "negative_cache" -> text = "Clear the cached entry with the fix below, then "
                + "run the check again to see what the upstream answers now.";
            case "bulkhead_full" -> text = "Raise Maximum and Initial permits under "
                + "Settings, Bulkhead, or find out why the upstream is slow.";
            case "circuit_open" -> text = "The breaker asks the upstream again by itself. "
                + "Check the upstream if it stays open.";
            case "cooldown_active" -> text = "See the cooldown check below.";
            case "authentication_required", "forbidden" ->
                text = "Use an account that may read this repository.";
            case "upstream_status_401", "upstream_status_403" ->
                text = "Check the credentials of the remote in the repository's settings.";
            case "upstream_status_429" ->
                text = "The upstream is throttling Pantera. Requests work again when it stops.";
            default -> text = "";
        }
        return text;
    }
}
