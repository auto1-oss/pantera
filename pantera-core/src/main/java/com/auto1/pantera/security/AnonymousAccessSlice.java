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
package com.auto1.pantera.security;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.headers.WwwAuthenticate;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Decorator that enforces per-repo anonymous-access policy. T-S07 of
 * {@code analysis/plan/v2/IMPLEMENTATION.md}.
 *
 * <p>The decorator sits OUTSIDE the per-adapter authentication chain.
 * Behaviour:</p>
 *
 * <ul>
 *   <li>Requests that carry any {@code Authorization} header are passed
 *     through unchanged — the downstream auth slice will validate the
 *     credentials and respond appropriately.</li>
 *   <li>Requests that carry NO {@code Authorization} header hit the
 *     {@link Policy}:
 *     <ul>
 *       <li>If the request method is read-like
 *         ({@code GET/HEAD/OPTIONS}) and {@link Policy#anonymousRead()}
 *         is {@code false}, return {@code 401 Unauthorized} with a
 *         {@code WWW-Authenticate: Basic realm="pantera"} challenge so
 *         standard tooling (mvn, npm, pip, docker login) prompts for
 *         credentials.</li>
 *       <li>If the request method is write-like (anything else) and
 *         {@link Policy#anonymousWrite()} is {@code false}, same
 *         {@code 401 Unauthorized} treatment.</li>
 *       <li>Otherwise, fall through unchanged.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>The decorator never inspects credential VALUES — it only checks
 * for the header's presence. Real credential validation is the job of
 * the downstream auth slice (Basic, Bearer, JWT, ...). This keeps the
 * decorator stateless and dirt-cheap on the hot path.</p>
 *
 * <p>The 401 response body is empty per RFC 7235: the
 * {@code WWW-Authenticate} header carries every operational signal the
 * client needs. A textual body would just leak the policy decision in
 * an unhelpful way.</p>
 *
 * @since 2.2.0
 */
public final class AnonymousAccessSlice implements Slice {

    /**
     * Per-repo policy: whether anonymous (no Authorization header)
     * reads and writes are allowed.
     */
    public record Policy(boolean anonymousRead, boolean anonymousWrite) {

        /**
         * Locked-down policy — both anonymous reads and writes denied.
         * This is the system-wide default applied by
         * {@code RepositorySlices.anonymousPolicy} when neither YAML
         * key is set; an admin must explicitly opt in to either flag
         * via {@code anonymous_read: true} / {@code anonymous_write: true}.
         *
         * <p>Historically called {@code hostedDefault} — kept under
         * that name for source compatibility, but it now describes the
         * default for <em>every</em> repo type, not just hosted.
         *
         * @return Policy with both anon read + write disabled.
         */
        public static Policy hostedDefault() {
            return new Policy(false, false);
        }

        /**
         * Open-read-locked-write policy — typical opt-in shape for a
         * public OSS-mirror proxy where {@code mvn} / {@code npm} /
         * {@code curl} should work without credentials but uploads
         * are reserved for authenticated callers.
         *
         * <p>This is NOT the production default for proxy repos — the
         * production default is {@link #hostedDefault()} (deny-by-
         * default). An admin opts in to this policy by setting
         * {@code anonymous_read: true} in the per-repo YAML.
         *
         * @return Policy with anon read enabled, anon write disabled.
         */
        public static Policy proxyDefault() {
            return new Policy(true, false);
        }
    }

    /**
     * HTTP methods treated as "reads" for the purpose of
     * {@link Policy#anonymousRead()}. The set is the safe / idempotent
     * subset of RFC 7231 §4.2 — anything not in here defaults to write.
     */
    private static final Set<String> READ_METHODS = Set.of(
        RqMethod.GET.value(),
        RqMethod.HEAD.value(),
        RqMethod.OPTIONS.value()
    );

    /**
     * {@code WWW-Authenticate} challenge value. {@code Basic} is a
     * universal-tooling baseline — every package manager handles it
     * (mvn, npm, pip, gem, docker login, ...). Bearer / JWT routes
     * can override this via the downstream auth slice.
     */
    private static final String CHALLENGE = "Basic realm=\"pantera\"";

    /**
     * Wrapped slice.
     */
    private final Slice origin;

    /**
     * Per-repo anonymous-access policy.
     */
    private final Policy policy;

    /**
     * Repository name (logging only).
     */
    private final String repoName;

    /**
     * Construct an enforcement decorator.
     *
     * @param origin   Wrapped slice (typically the per-adapter slice).
     * @param policy   Per-repo policy.
     * @param repoName Repository name for log correlation.
     */
    public AnonymousAccessSlice(
        final Slice origin, final Policy policy, final String repoName
    ) {
        this.origin = origin;
        this.policy = policy;
        this.repoName = repoName;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        if (hasAuthorization(headers)) {
            return this.origin.response(line, headers, body);
        }
        final boolean isRead = isRead(line);
        final boolean allowed = isRead ? this.policy.anonymousRead()
            : this.policy.anonymousWrite();
        if (allowed) {
            return this.origin.response(line, headers, body);
        }
        EcsLogger.info("com.auto1.pantera.security")
            .message(
                "Rejecting anonymous "
                    + (isRead ? "read" : "write")
                    + " on private repo"
            )
            .eventCategory("authentication")
            .eventAction("anonymous_reject")
            .eventOutcome("failure")
            .field("event.reason", isRead
                ? "anonymous_read_disabled"
                : "anonymous_write_disabled")
            .field("http.request.method", line.method().value())
            .field("url.path", line.uri().getPath())
            .field("repository.name", this.repoName)
            .field("http.response.status_code", 401)
            .field("log.source", "http")
            .log();
        // Drain (never materialise) the body so Vert.x doesn't leak the
        // request publisher. asBytesFuture() here pre-allocated from the
        // attacker-declared Content-Length before the 401 was even written
        // (resource-dos F31, 2.2.9).
        return body.discard().thenApply(ignored ->
            ResponseBuilder.unauthorized()
                .header(new WwwAuthenticate(CHALLENGE))
                .build()
        );
    }

    /**
     * @param headers Request headers.
     * @return {@code true} if any {@code Authorization} header is
     *     present, regardless of scheme or value.
     */
    private static boolean hasAuthorization(final Headers headers) {
        final String target = Authorization.NAME.toLowerCase(Locale.ROOT);
        for (final Header h : headers) {
            if (h.getKey().toLowerCase(Locale.ROOT).equals(target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param line Request line.
     * @return {@code true} if the method is read-like (GET, HEAD, OPTIONS).
     */
    private static boolean isRead(final RequestLine line) {
        return READ_METHODS.contains(line.method().value());
    }
}
