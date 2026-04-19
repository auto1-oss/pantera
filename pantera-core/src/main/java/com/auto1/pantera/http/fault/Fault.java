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

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Sealed fault taxonomy for Pantera request paths.
 *
 * <p>Every slice that can signal a problem does so by returning a {@link Result.Err}
 * carrying one of these variants instead of throwing. A single
 * {@link FaultTranslator} owns the HTTP-status policy — see §9 of
 * {@code docs/analysis/v2.2-target-architecture.md}.
 *
 * <p>Adding a new variant here is a deliberately breaking change: every exhaustive
 * {@code switch} on {@code Fault} must be updated. See
 * {@code FaultTranslatorTest#exhaustiveSwitchCompilesForEveryVariant} for the
 * compile-time guard.
 *
 * @since 2.2.0
 */
public sealed interface Fault {

    /** 404: artifact does not exist in this scope. */
    record NotFound(String scope, String artifact, String version) implements Fault {
    }

    /** 403: cooldown, auth rejected, or explicit block. */
    record Forbidden(String reason) implements Fault {
    }

    /** 500: index/DB unavailable (timeout, connection failure, statement timeout). */
    record IndexUnavailable(Throwable cause, String query) implements Fault {
    }

    /** 500: storage read failed (IO error, ValueNotFoundException on sidecar, etc). */
    record StorageUnavailable(Throwable cause, String key) implements Fault {
    }

    /**
     * No 2xx winner across proxy members. Carries the outcomes AND the winning
     * proxy {@link Response} (if any proxy produced one) so {@link FaultTranslator}
     * can stream it verbatim to the client. When no member produced a Response
     * at all (all threw / cancelled / timed out), {@code winningResponse} is empty
     * and {@code FaultTranslator} synthesizes a 502.
     *
     * @param group           Group repository name.
     * @param outcomes        Full list of member outcomes — always populated,
     *                        one entry per attempted member.
     * @param winningResponse The {@link ProxyFailure} chosen by
     *                        {@link FaultTranslator#pickWinningFailure(List)}, or
     *                        {@link Optional#empty()} if every member
     *                        threw / was cancelled / timed out.
     */
    record AllProxiesFailed(
        String group,
        List<MemberOutcome> outcomes,
        Optional<ProxyFailure> winningResponse
    ) implements Fault {

        /**
         * A member that produced an HTTP response but not a 2xx success. The
         * {@code response} is held so the translator can stream its status, headers,
         * and body verbatim.
         *
         * @param memberName Declaration-order name of the member.
         * @param response   Upstream response (any non-2xx status).
         */
        public record ProxyFailure(String memberName, Response response) {
        }
    }

    /** 500: programming error, NPE, queue overflow, classifier default. */
    record Internal(Throwable cause, String where) implements Fault {
    }

    /** 504: end-to-end deadline exceeded. */
    record Deadline(Duration budget, String where) implements Fault {
    }

    /** 503: bulkhead / rate limiter rejected. Carries suggested retry-after. */
    record Overload(String resource, Duration retryAfter) implements Fault {
    }

    /**
     * 502: upstream-claimed checksum disagrees with bytes Pantera just received.
     * See §9.5 of the target architecture doc — the proxy cache writer rejects
     * a primary/sidecar pair whose digest does not match the sidecar claim.
     *
     * @param upstreamUri  URI of the primary artifact that failed verification.
     * @param algo         Checksum algorithm whose sidecar disagreed.
     * @param sidecarClaim Hex-encoded digest declared by the sidecar.
     * @param computed     Hex-encoded digest Pantera computed over the streamed bytes.
     */
    record UpstreamIntegrity(
        String upstreamUri,
        ChecksumAlgo algo,
        String sidecarClaim,
        String computed
    ) implements Fault {
    }

    /**
     * Per-member outcome in a proxy fanout. Used by
     * {@link AllProxiesFailed#outcomes()} so the translator and the audit log
     * can reason about exactly what happened at each member.
     *
     * <p>{@code response} is present when the member produced an HTTP response
     * (kind in {@code OK}, {@code NOT_FOUND}, {@code FIVE_XX}); empty when the
     * member threw / was cancelled / was skipped due to circuit-breaker.
     * {@link FaultTranslator#pickWinningFailure(List)} reads this field to
     * choose the best response to pass through.
     *
     * @param member   Member repository name (declaration order).
     * @param kind     Outcome classification.
     * @param cause    Throwable if the outcome was {@code EXCEPTION}, else {@code null}.
     * @param response Upstream response if the member produced one, else empty.
     */
    record MemberOutcome(String member, Kind kind, Throwable cause, Optional<Response> response) {

        /**
         * Convenience factory for outcomes with no response (exception / cancelled /
         * circuit-open).
         *
         * @param member Member name.
         * @param kind   Outcome kind.
         * @param cause  Underlying throwable, may be {@code null}.
         * @return A MemberOutcome with {@link Optional#empty()} response.
         */
        public static MemberOutcome threw(final String member, final Kind kind, final Throwable cause) {
            return new MemberOutcome(member, kind, cause, Optional.empty());
        }

        /**
         * Convenience factory for outcomes with an HTTP response (2xx / 4xx / 5xx).
         *
         * @param member   Member name.
         * @param kind     Outcome kind.
         * @param response Upstream response.
         * @return A MemberOutcome with the response attached and no cause.
         */
        public static MemberOutcome responded(final String member, final Kind kind, final Response response) {
            return new MemberOutcome(member, kind, null, Optional.of(response));
        }

        /** Outcome classification for a single proxy member. */
        public enum Kind {
            /** Member returned 2xx — included for completeness, not used in AllProxiesFailed construction. */
            OK,
            /** Member returned 404. */
            NOT_FOUND,
            /** Member returned 5xx. */
            FIVE_XX,
            /** Member threw (timeout, IOException, ConnectException, etc). */
            EXCEPTION,
            /** Member was cancelled (race winner already found elsewhere). */
            CANCELLED,
            /** Circuit breaker was open for this member at dispatch time. */
            CIRCUIT_OPEN
        }
    }

    /**
     * Supported checksum algorithms for proxy-cache integrity verification.
     * See §9.5.
     */
    enum ChecksumAlgo {
        MD5,
        SHA1,
        SHA256,
        SHA512
    }
}
