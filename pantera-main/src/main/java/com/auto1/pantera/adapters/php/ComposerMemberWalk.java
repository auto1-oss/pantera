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
package com.auto1.pantera.adapters.php;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.cooldown.response.CooldownResponseFactory;
import com.auto1.pantera.group.SliceResolver;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sequential walk over Composer group members for the metadata paths the
 * group answers itself ({@code packages.json} and {@code /p2/...}).
 *
 * <p>Per-member outcome, mirroring {@code GroupResolver}:</p>
 * <ul>
 *   <li>200, 401/403 and a cooldown verdict are authoritative: the walk
 *       stops and the response is relayed;</li>
 *   <li>404 / 410 are misses: the walk continues;</li>
 *   <li>anything else (5xx, other 4xx, an exception) is a member failure:
 *       the walk continues and the failure is remembered, so a walk that
 *       ends without an answer is reported as unavailable (503 +
 *       {@code Retry-After}), never as a 404 that would outlive the
 *       outage.</li>
 * </ul>
 *
 * @since 2.2.9
 */
final class ComposerMemberWalk {

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.adapters.php";

    /**
     * Minimum {@code Retry-After} of an all-members-unavailable answer.
     */
    private static final long MIN_RETRY_AFTER = 5L;

    /**
     * Member slice resolver.
     */
    private final SliceResolver resolver;

    /**
     * Server port for resolving member slices.
     */
    private final int port;

    /**
     * Group repository name (for logs).
     */
    private final String group;

    /**
     * Ctor.
     *
     * @param resolver Member slice resolver
     * @param port Server port
     * @param group Group repository name
     */
    ComposerMemberWalk(final SliceResolver resolver, final int port, final String group) {
        this.resolver = resolver;
        this.port = port;
        this.group = group;
    }

    /**
     * Ask members in order; complete with the first authoritative answer, or
     * empty when every member missed or failed (failures land in
     * {@code state}). Non-answer bodies are drained.
     *
     * @param members Members to ask, in order
     * @param line Group request line (member name is prepended)
     * @param headers Request headers (already sanitised)
     * @param state Walk state collecting failures
     * @return First authoritative member response, if any
     */
    CompletableFuture<Optional<Response>> first(
        final List<String> members,
        final RequestLine line,
        final Headers headers,
        final State state
    ) {
        return this.from(members, 0, line, headers, state);
    }

    /**
     * Terminal answer of a walk without an authoritative response.
     *
     * @param state Walk state
     * @param line Group request line (for logs)
     * @return 503 + Retry-After when a member failed, else 404
     */
    Response exhausted(final State state, final RequestLine line) {
        if (!state.failed()) {
            return ResponseBuilder.notFound().build();
        }
        final long retry = Math.max(MIN_RETRY_AFTER, state.retryAfter());
        EcsLogger.warn(LOGGER)
            .message(
                "No Composer group member could answer; returning 503, Retry-After "
                    + retry + "s"
            )
            .eventCategory("network")
            .eventAction("group_all_members_unavailable")
            .eventOutcome("failure")
            .field("repository.name", this.group)
            .field("url.path", line.uri().getPath())
            .field("log.source", "application")
            .log();
        final ResponseBuilder builder = ResponseBuilder.from(RsStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", Long.toString(retry));
        if (state.circuitOpen()) {
            builder.header(UpstreamCircuitOpenException.HEADER, "true");
        }
        return builder
            .textBody("Composer group members are temporarily unavailable")
            .build();
    }

    /**
     * Whether a member response ends the walk.
     *
     * @param resp Member response
     * @return True for 200, 401/403 and a cooldown verdict
     */
    static boolean authoritative(final Response resp) {
        final RsStatus status = resp.status();
        return status == RsStatus.OK
            || status == RsStatus.FORBIDDEN
            || status == RsStatus.UNAUTHORIZED
            || !resp.headers().values(CooldownResponseFactory.HEADER).isEmpty();
    }

    /**
     * Rewrite a group request line to address a member.
     *
     * @param original Group request line
     * @param member Member repository name
     * @return Member request line
     */
    static RequestLine forMember(final RequestLine original, final String member) {
        final String path = original.uri().getPath();
        final StringBuilder full = new StringBuilder(
            path.startsWith("/") ? "/" + member + path : "/" + member + "/" + path
        );
        if (original.uri().getQuery() != null) {
            full.append('?').append(original.uri().getQuery());
        }
        return new RequestLine(original.method().value(), full.toString(), original.version());
    }

    /**
     * Walk from {@code idx}.
     */
    private CompletableFuture<Optional<Response>> from(
        final List<String> members,
        final int idx,
        final RequestLine line,
        final Headers headers,
        final State state
    ) {
        if (idx >= members.size()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        final String member = members.get(idx);
        CompletableFuture<Response> call;
        try {
            call = this.resolver.slice(new Key.From(member), this.port, 0)
                .response(ComposerMemberWalk.forMember(line, member), headers, Content.EMPTY);
        } catch (final RuntimeException ex) {
            call = CompletableFuture.failedFuture(ex);
        }
        return call.handle((resp, err) -> {
            if (err != null) {
                this.memberFailed(member, line, err.getMessage());
                state.fail(false, 0L);
                return this.from(members, idx + 1, line, headers, state);
            }
            if (ComposerMemberWalk.authoritative(resp)) {
                return CompletableFuture.completedFuture(Optional.of(resp));
            }
            return resp.body().asBytesFuture().thenCompose(drained -> {
                this.classify(member, line, resp, state);
                return this.from(members, idx + 1, line, headers, state);
            });
        }).thenCompose(next -> next);
    }

    /**
     * Record a non-answer: a miss (404/410) or a member failure.
     */
    private void classify(
        final String member, final RequestLine line, final Response resp, final State state
    ) {
        final int code = resp.status().code();
        if (code == RsStatus.NOT_FOUND.code() || code == 410) {
            return;
        }
        final boolean marker = !resp.headers()
            .values(UpstreamCircuitOpenException.HEADER).isEmpty();
        state.fail(marker, ComposerMemberWalk.retryAfter(resp));
        this.memberFailed(member, line, "HTTP " + code);
    }

    /**
     * Log a member failure.
     */
    private void memberFailed(final String member, final RequestLine line, final String reason) {
        EcsLogger.warn(LOGGER)
            .message("Composer group member '" + member + "' failed (" + reason + "); trying next")
            .eventCategory("web")
            .eventAction("group_member_fallthrough")
            .eventOutcome("failure")
            .field("repository.name", this.group)
            .field("url.path", line.uri().getPath())
            .field("log.source", "application")
            .log();
    }

    /**
     * Delta-seconds {@code Retry-After} of a response; 0 when absent.
     */
    private static long retryAfter(final Response resp) {
        final List<String> values = resp.headers().values("Retry-After");
        if (values.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(values.get(0).trim());
        } catch (final NumberFormatException ignored) {
            return 0L;
        }
    }

    /**
     * Mutable state of one walk.
     */
    static final class State {
        /**
         * Whether any member failed.
         */
        private final AtomicBoolean failure = new AtomicBoolean(false);

        /**
         * Whether a failure carried the upstream circuit-open marker.
         */
        private final AtomicBoolean open = new AtomicBoolean(false);

        /**
         * Largest Retry-After hint seen.
         */
        private final AtomicLong retry = new AtomicLong(0L);

        /**
         * Record a failure.
         *
         * @param marker Whether the failure carried the circuit-open marker
         * @param hint Retry-After hint in seconds (0 when unknown)
         */
        void fail(final boolean marker, final long hint) {
            this.failure.set(true);
            if (marker) {
                this.open.set(true);
            }
            this.retry.accumulateAndGet(hint, Math::max);
        }

        /**
         * @return Whether any member failed
         */
        boolean failed() {
            return this.failure.get();
        }

        /**
         * @return Whether a failure carried the circuit-open marker
         */
        boolean circuitOpen() {
            return this.open.get();
        }

        /**
         * @return Largest Retry-After hint seen
         */
        long retryAfter() {
            return this.retry.get();
        }
    }
}
