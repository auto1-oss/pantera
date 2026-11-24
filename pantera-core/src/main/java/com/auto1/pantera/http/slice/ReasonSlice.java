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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.cooldown.response.CooldownResponseFactory;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.LocalRefusal;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.fault.FaultTranslator;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.AnswerReason;
import com.auto1.pantera.http.log.AnsweringRepository;
import com.auto1.pantera.http.log.FailureCause;
import com.auto1.pantera.http.log.ReasonWeight;
import com.auto1.pantera.http.log.RequestReasons;
import com.auto1.pantera.http.log.RequestTrace;
import com.auto1.pantera.http.log.StatusReason;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Puts on every answer of a repository with a status of 400 or above the
 * reason why it was given (see {@link AnswerReason}), so that the access
 * record of the request can say it.
 *
 * <p>It opens a slot in {@link RequestReasons} around the repository's
 * handling of the request and names it to the repository in a request
 * header. What the repository notes there (a negative-cache hit, what the
 * upstream answered, the failure of an upstream call) is the reason. When it
 * noted nothing, the reason is read from a marker the answer carries (open
 * circuit breaker, full bulkhead, cooldown block, translated fault), and
 * failing that it names the status.</p>
 *
 * <p>The answer may already carry a reason: a group relays the answer of a
 * member, and that member has its own {@code ReasonSlice}. A relayed 401 or
 * 403 keeps it, since the member that gave it ended the walk. For any other
 * status the reason that explains most wins, between the one the answer
 * carries and those noted for this repository: the 404 of a group is a cached
 * one if any member answered from its negative cache, whichever member's 404
 * the group relays.</p>
 *
 * @since 2.2.12
 */
public final class ReasonSlice implements Slice {

    /**
     * Lowest status that needs a reason.
     */
    private static final int ERRORS = 400;

    /**
     * Repository slice.
     */
    private final Slice origin;

    /**
     * Repository name, for the trace of a traced request.
     */
    private final String name;

    /**
     * Repository type, for the trace of a traced request.
     */
    private final String type;

    /**
     * Ctor for a slice that is not a named repository.
     * @param origin Slice
     */
    public ReasonSlice(final Slice origin) {
        this(origin, "", "");
    }

    /**
     * Ctor.
     * @param origin Repository slice
     * @param name Repository name
     * @param type Repository type, such as {@code maven-proxy}
     */
    public ReasonSlice(final Slice origin, final String name, final String type) {
        this.origin = origin;
        this.name = name;
        this.type = type;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final RequestReasons reasons = RequestReasons.instance();
        final String slot = reasons.open();
        final List<Header> inner = new ArrayList<>(headers.asList().size() + 1);
        for (final Header header : headers) {
            if (!RequestReasons.SLOT_HEADER.equalsIgnoreCase(header.getKey())) {
                inner.add(header);
            }
        }
        inner.add(new Header(RequestReasons.SLOT_HEADER, slot));
        // Only a request the troubleshooter replays is traced: it then shows
        // what this repository answered, why, and how long it took.
        final boolean traced = RequestTrace.instance().traced(headers);
        final long started = traced ? System.nanoTime() : 0L;
        return this.origin.response(line, new Headers(inner), body)
            .whenComplete(
                (response, error) -> {
                    if (error != null) {
                        reasons.close(slot);
                        if (traced) {
                            this.traced(headers, 0, new FailureCause(error).reason(), started);
                        }
                    }
                }
            )
            .thenApply(
                response -> {
                    // Name this repository on the answer for the access
                    // record (a group replaces the stamp of its member).
                    final Response explained = new AnsweringRepository(
                        ReasonSlice.explained(response, reasons.close(slot))
                    ).stamped(this.name, this.type);
                    if (traced) {
                        final int code = explained.status().code();
                        this.traced(
                            headers, code,
                            code < ReasonSlice.ERRORS
                                ? null : new AnswerReason(explained).token().orElse(null),
                            started
                        );
                    }
                    return explained;
                }
            );
    }

    /**
     * Record this repository's answer in the trace of the request.
     * @param headers Headers of the request
     * @param status Status answered, 0 when the repository failed
     * @param reason Reason of a status of 400 or above, or of the failure
     * @param started When the repository was asked, {@link System#nanoTime()}
     */
    private void traced(
        final Headers headers, final int status, final String reason, final long started
    ) {
        RequestTrace.instance().step(
            headers,
            new RequestTrace.Step(
                this.name, this.type, status, reason,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            )
        );
    }

    /**
     * The answer with its reason.
     * @param response Answer of the repository
     * @param noted Reason noted while the repository handled the request
     * @return The answer, stamped when its status is 400 or above
     */
    private static Response explained(final Response response, final Optional<String> noted) {
        final int code = response.status().code();
        final Response result;
        if (code < ReasonSlice.ERRORS) {
            result = response;
        } else {
            final AnswerReason answer = new AnswerReason(response);
            final Optional<String> held = answer.token();
            // A 401 or 403 that came with a reason was relayed from the
            // member that ended the walk: what was noted about the members
            // before it does not explain it.
            final Optional<String> told = held.isPresent() && ReasonSlice.authoritative(code)
                ? Optional.empty() : noted.filter(ReasonSlice::telling);
            result = answer.stamped(
                ReasonSlice.heavier(held.filter(ReasonSlice::telling), told)
                    .or(() -> ReasonSlice.marked(response))
                    .orElseGet(() -> new StatusReason(code).token())
            );
        }
        return result;
    }

    /**
     * Of the reason the answer came with and the one noted for this
     * repository, the one that explains more; the answer's own when they
     * explain as much.
     * @param held Reason the answer came with
     * @param told Reason noted while the repository handled the request
     * @return The reason to report, empty when there is neither
     */
    private static Optional<String> heavier(
        final Optional<String> held, final Optional<String> told
    ) {
        final Optional<String> reason;
        if (held.isPresent() && (told.isEmpty()
            || new ReasonWeight(held.get()).value() >= new ReasonWeight(told.get()).value())) {
            reason = held;
        } else {
            reason = told;
        }
        return reason;
    }

    /**
     * Whether a reason says more than the name of a status. A status name
     * belongs to the answer it was given for: it is never carried over to
     * an answer of another layer, which may have another status.
     * @param reason Reason token
     * @return True if the reason explains something
     */
    private static boolean telling(final String reason) {
        return new ReasonWeight(reason).value() > 0;
    }

    /**
     * Whether the member that gave this status ended the walk of a group.
     * @param code Status code
     * @return True for 401 and 403
     */
    private static boolean authoritative(final int code) {
        return code == 401 || code == 403;
    }

    /**
     * The reason a marker header of the answer stands for.
     * @param response Answer
     * @return Reason, empty when the answer carries no marker
     */
    private static Optional<String> marked(final Response response) {
        final Optional<String> reason;
        if (!response.headers().values(LocalRefusal.BULKHEAD).isEmpty()) {
            reason = Optional.of("bulkhead_full");
        } else if (!response.headers().values(UpstreamCircuitOpenException.HEADER).isEmpty()) {
            reason = Optional.of("circuit_open");
        } else if (!response.headers().values(CooldownResponseFactory.HEADER).isEmpty()) {
            reason = Optional.of("cooldown_active");
        } else {
            reason = response.headers().values(FaultTranslator.HEADER_FAULT).stream()
                .findFirst().map(ReasonSlice::fault);
        }
        return reason;
    }

    /**
     * Reason token of a fault tag such as {@code index-unavailable} or
     * {@code overload:maven-central}.
     * @param tag Value of the fault header
     * @return Reason token
     */
    private static String fault(final String tag) {
        final int colon = tag.indexOf(':');
        final String name = (colon < 0 ? tag : tag.substring(0, colon))
            .toLowerCase(Locale.ROOT).replace('-', '_');
        return "internal".equals(name) ? "internal_error" : name;
    }
}
