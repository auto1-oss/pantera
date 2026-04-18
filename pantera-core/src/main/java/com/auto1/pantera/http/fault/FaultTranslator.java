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
package com.auto1.pantera.http.fault;

import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.context.RequestContext;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Single decision point for "what HTTP status + headers + body does this
 * {@link Fault} produce". See §9 of
 * {@code docs/analysis/v2.2-target-architecture.md}.
 *
 * <p>Policy, codified:
 * <ul>
 *   <li>{@link Fault.NotFound}            — 404</li>
 *   <li>{@link Fault.Forbidden}           — 403</li>
 *   <li>{@link Fault.IndexUnavailable}    — 500 with {@code X-Pantera-Fault: index-unavailable}</li>
 *   <li>{@link Fault.StorageUnavailable}  — 500 with {@code X-Pantera-Fault: storage-unavailable}</li>
 *   <li>{@link Fault.Internal}            — 500 with {@code X-Pantera-Fault: internal}</li>
 *   <li>{@link Fault.Deadline}            — 504 with {@code X-Pantera-Fault: deadline-exceeded}</li>
 *   <li>{@link Fault.Overload}            — 503 + {@code Retry-After} + {@code X-Pantera-Fault: overload:&lt;resource&gt;}</li>
 *   <li>{@link Fault.AllProxiesFailed}    — pass-through of the winning proxy response, or synthetic 502</li>
 *   <li>{@link Fault.UpstreamIntegrity}   — 502 with {@code X-Pantera-Fault: upstream-integrity:&lt;algo&gt;}</li>
 * </ul>
 *
 * @since 2.2.0
 */
public final class FaultTranslator {

    /** Response header used to tag every translated fault with a stable identifier. */
    public static final String HEADER_FAULT = "X-Pantera-Fault";

    /** Response header emitted on AllProxiesFailed pass-through with the fanout size. */
    public static final String HEADER_PROXIES_TRIED = "X-Pantera-Proxies-Tried";

    /** Tag value for index-unavailable faults. */
    static final String TAG_INDEX = "index-unavailable";

    /** Tag value for storage-unavailable faults. */
    static final String TAG_STORAGE = "storage-unavailable";

    /** Tag value for generic internal faults. */
    static final String TAG_INTERNAL = "internal";

    /** Tag value for deadline-exceeded faults. */
    static final String TAG_DEADLINE = "deadline-exceeded";

    /** Prefix for overload fault tags; {@code resource} is appended verbatim. */
    static final String TAG_OVERLOAD_PREFIX = "overload:";

    /** Prefix for proxy-failed fault tags; member name is appended. */
    static final String TAG_PROXIES_FAILED_PREFIX = "proxies-failed:";

    /** Tag value for the "nobody responded" synthesized 502. */
    static final String TAG_PROXIES_NONE_RESPONDED = "proxies-failed:none-responded";

    /** Prefix for upstream-integrity fault tags; {@code algo} is appended. */
    static final String TAG_UPSTREAM_INTEGRITY_PREFIX = "upstream-integrity:";

    /**
     * Ranking table for retryable 5xx statuses. Index 0 is the most preferred;
     * higher indices are worse. Unlisted statuses fall into a catch-all tier
     * that ranks after every listed status but before non-5xx responses.
     */
    private static final List<Integer> RETRYABILITY_ORDER = List.of(503, 504, 502, 500);

    /** Rank assigned to unlisted 5xx statuses. */
    private static final int RANK_OTHER_5XX = RETRYABILITY_ORDER.size();

    /** Rank assigned to any non-5xx response that made it to the winner pool. */
    private static final int RANK_NON_5XX = RETRYABILITY_ORDER.size() + 1;

    private FaultTranslator() {
    }

    /**
     * Translate a {@link Fault} into the outbound {@link Response} a client will
     * see.
     *
     * <p>Exhaustive switch — adding a new {@link Fault} variant is a compile
     * error here until the new case is handled.
     *
     * @param fault The fault to translate. Never null.
     * @param ctx   Per-request context. Currently unused by this method but
     *              passed through so later WIs can attach {@code trace.id}
     *              headers and correlated body fields without breaking the API.
     * @return The outbound response.
     */
    @SuppressWarnings({"PMD.UnusedFormalParameter", "PMD.CyclomaticComplexity"})
    public static Response translate(final Fault fault, final RequestContext ctx) {
        return switch (fault) {
            case Fault.NotFound nf -> ResponseBuilder.notFound().build();
            case Fault.Forbidden fb -> ResponseBuilder.forbidden()
                .textBody(fb.reason())
                .build();
            case Fault.IndexUnavailable iu -> internalWithTag(TAG_INDEX);
            case Fault.StorageUnavailable su -> internalWithTag(TAG_STORAGE);
            case Fault.Internal i -> internalWithTag(TAG_INTERNAL);
            case Fault.Deadline d -> ResponseBuilder.gatewayTimeout()
                .header(HEADER_FAULT, TAG_DEADLINE)
                .build();
            case Fault.Overload ov -> ResponseBuilder.from(RsStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", Long.toString(ov.retryAfter().toSeconds()))
                .header(HEADER_FAULT, TAG_OVERLOAD_PREFIX + ov.resource())
                .build();
            case Fault.AllProxiesFailed apf -> passThroughProxy(apf);
            case Fault.UpstreamIntegrity ui -> ResponseBuilder.badGateway()
                .header(HEADER_FAULT, TAG_UPSTREAM_INTEGRITY_PREFIX + ui.algo().name())
                .build();
        };
    }

    /**
     * Pick the "best" proxy response to pass through when all members failed.
     * Ranking follows §2 of the target architecture doc:
     *
     * <ol>
     *   <li><b>Retryability</b> — {@code 503 > 504 > 502 > 500 > other 5xx}.
     *       Clients retry transient statuses; we prefer the more-retryable
     *       answer so downstream callers do the right thing.</li>
     *   <li><b>Has body</b> — non-empty body wins over an empty one
     *       (diagnostic information).</li>
     *   <li><b>Declaration order</b> — earliest-declared member wins among
     *       ties (deterministic).</li>
     * </ol>
     *
     * <p>Members with {@link Fault.MemberOutcome.Kind#EXCEPTION},
     * {@link Fault.MemberOutcome.Kind#CANCELLED}, or
     * {@link Fault.MemberOutcome.Kind#CIRCUIT_OPEN} contribute no Response and
     * are ignored.
     *
     * @param outcomes Full list of member outcomes, in declaration order.
     * @return The chosen {@link Fault.AllProxiesFailed.ProxyFailure}, or
     *         {@link Optional#empty()} if no member produced a response.
     */
    public static Optional<Fault.AllProxiesFailed.ProxyFailure>
            pickWinningFailure(final List<Fault.MemberOutcome> outcomes) {
        Fault.MemberOutcome best = null;
        int bestIndex = -1;
        for (int idx = 0; idx < outcomes.size(); idx++) {
            final Fault.MemberOutcome candidate = outcomes.get(idx);
            if (candidate.response().isEmpty()) {
                continue;
            }
            if (best == null || compareOutcomes(candidate, idx, best, bestIndex) < 0) {
                best = candidate;
                bestIndex = idx;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        return Optional.of(
            new Fault.AllProxiesFailed.ProxyFailure(best.member(), best.response().orElseThrow())
        );
    }

    /**
     * Compare two candidate outcomes that both carry a {@link Response}. Returns
     * a negative number if {@code a} is better, positive if {@code b} is better,
     * 0 only when they are indistinguishable (should not occur because
     * declaration-order breaks the final tie).
     */
    private static int compareOutcomes(
        final Fault.MemberOutcome a, final int aIdx,
        final Fault.MemberOutcome b, final int bIdx
    ) {
        return Comparator
            .comparingInt((Integer[] pair) -> pair[0]) // retryability rank, smaller is better
            .thenComparingInt(pair -> pair[1])          // body rank, smaller is better
            .thenComparingInt(pair -> pair[2])          // declaration index, smaller is better
            .compare(
                rankingKey(a, aIdx),
                rankingKey(b, bIdx)
            );
    }

    private static Integer[] rankingKey(final Fault.MemberOutcome outcome, final int idx) {
        final Response resp = outcome.response().orElseThrow();
        return new Integer[] {
            retryabilityRank(resp.status()),
            resp.body().size().orElse(0L) > 0L ? 0 : 1,
            idx
        };
    }

    private static int retryabilityRank(final RsStatus status) {
        if (!status.serverError()) {
            return RANK_NON_5XX;
        }
        final int pos = RETRYABILITY_ORDER.indexOf(status.code());
        return pos >= 0 ? pos : RANK_OTHER_5XX;
    }

    /**
     * Build a 500 response tagged with {@code X-Pantera-Fault: &lt;tag&gt;}.
     */
    private static Response internalWithTag(final String tag) {
        return ResponseBuilder.internalError()
            .header(HEADER_FAULT, tag)
            .build();
    }

    /**
     * Pass through the "best" proxy 5xx response verbatim. If no proxy produced
     * a Response at all, synthesize a plain 502 — this is the only
     * AllProxiesFailed path that invents a status code.
     */
    private static Response passThroughProxy(final Fault.AllProxiesFailed apf) {
        final String proxiesTried = Integer.toString(apf.outcomes().size());
        final Optional<Fault.AllProxiesFailed.ProxyFailure> winning = apf.winningResponse();
        if (winning.isPresent()) {
            final Fault.AllProxiesFailed.ProxyFailure pf = winning.orElseThrow();
            final Response upstream = pf.response();
            return ResponseBuilder.from(upstream.status())
                .headers(upstream.headers())
                .header(HEADER_FAULT, TAG_PROXIES_FAILED_PREFIX + pf.memberName())
                .header(HEADER_PROXIES_TRIED, proxiesTried)
                .body(upstream.body())
                .build();
        }
        return ResponseBuilder.badGateway()
            .header(HEADER_FAULT, TAG_PROXIES_NONE_RESPONDED)
            .header(HEADER_PROXIES_TRIED, proxiesTried)
            .jsonBody("{\"error\":\"all upstream members failed\"}")
            .build();
    }
}
