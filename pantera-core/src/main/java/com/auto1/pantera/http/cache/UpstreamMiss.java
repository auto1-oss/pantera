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
package com.auto1.pantera.http.cache;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.LocalRefusal;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.UpstreamStatusException;
import com.auto1.pantera.http.log.AnswerReason;
import com.auto1.pantera.http.log.FailureCause;
import com.auto1.pantera.http.log.RequestReasons;
import com.auto1.pantera.http.log.ServerErrorLog;
import java.util.List;

/**
 * The answer of a proxy repository whose upstream gave no content.
 *
 * <p>"The upstream has no such file" and "the upstream could not be asked"
 * are different answers. A proxy that turns both into a bare 404 makes a
 * build report a missing dependency during an upstream outage, hides which
 * of the two happened from the access log, and lets a group or a negative
 * cache remember a file as absent that merely could not be fetched. This
 * class is the one place that maps the outcome of an upstream call to the
 * client answer and says why:</p>
 *
 * <ul>
 *   <li>upstream 404 / 410: 404, reason {@code upstream_status_<code>};</li>
 *   <li>another upstream 4xx (401, 403, 400, ...): 404 as well, since the
 *     refusal concerns Pantera's request and not the client's, with reason
 *     {@code upstream_status_<code>} so that it is never read as "not
 *     found", and with {@link NegativeCache#SKIP_HEADER} so that no negative
 *     cache remembers it (the convention of the Maven and npm proxies);</li>
 *   <li>upstream 429, and upstream 503 with a {@code Retry-After}: 503 with
 *     that {@code Retry-After};</li>
 *   <li>any other upstream 5xx: 502;</li>
 *   <li>a local refusal (open circuit breaker, full bulkhead): its status
 *     class is kept and so are its marker and {@code Retry-After}, so that a
 *     group skips this member instead of counting a failure;</li>
 *   <li>a failed call (timeout, refused or lost connection, TLS): 502, the
 *     circuit-open marker restored from
 *     {@link UpstreamCircuitOpenException}; an upstream answer carried as an
 *     {@link UpstreamStatusException} is mapped as that answer.</li>
 * </ul>
 *
 * <p>Every 5xx is logged once through {@link ServerErrorLog}, which also
 * notes the reason for the access record; the 4xx answers only note it. The
 * answer also carries its reason in the internal {@link AnswerReason#HEADER},
 * for a caller that no longer holds the client's request headers. None
 * of these answers may be written to a negative cache except the one for an
 * upstream 404 / 410.</p>
 *
 * @since 2.2.14
 */
public final class UpstreamMiss {

    /**
     * Retry-After header name.
     */
    private static final String RETRY_AFTER = "Retry-After";

    /**
     * Body of a 5xx answer. Names no cause: upstream error pages and
     * exception text can carry internal host names.
     */
    private static final String UNAVAILABLE = "Upstream temporarily unavailable";

    /**
     * Logger name of the cause record.
     */
    private final String logger;

    /**
     * Repository name.
     */
    private final String repo;

    /**
     * Request path.
     */
    private final String path;

    /**
     * Headers of the client request (reason slot, trace id).
     */
    private final Headers request;

    /**
     * Ctor.
     * @param logger Logger name of the cause record
     * @param repo Repository name
     * @param path Request path
     * @param request Headers of the client request
     */
    public UpstreamMiss(
        final String logger, final String repo, final String path, final Headers request
    ) {
        this.logger = logger;
        this.repo = repo;
        this.path = path;
        this.request = request;
    }

    /**
     * The upstream answered a status that is not 2xx.
     * @param code Status the upstream answered
     * @param upstream Headers of the upstream answer
     * @return Answer for the client
     */
    public Response answered(final int code, final Headers upstream) {
        final Response answer;
        if (code >= 500 && UpstreamMiss.refused(upstream)) {
            answer = this.refusal(code, upstream);
        } else if (code >= 500 || code == RsStatus.TOO_MANY_REQUESTS.code()) {
            final boolean backoff = code == RsStatus.TOO_MANY_REQUESTS.code()
                || code == RsStatus.SERVICE_UNAVAILABLE.code()
                && !upstream.values(UpstreamMiss.RETRY_AFTER).isEmpty();
            final ResponseBuilder builder = backoff
                ? ResponseBuilder.unavailable() : ResponseBuilder.badGateway();
            UpstreamMiss.retryAfter(builder, upstream);
            answer = builder.textBody(UpstreamMiss.UNAVAILABLE).build();
            this.cause(answer).upstreamStatus(code).warn("Upstream answered " + code);
        } else {
            RequestReasons.instance().note(this.request, RequestReasons.upstream(code));
            final ResponseBuilder builder = ResponseBuilder.notFound();
            if (code != RsStatus.NOT_FOUND.code() && code != RsStatus.GONE.code()) {
                builder.header(NegativeCache.SKIP_HEADER, "true");
            }
            answer = builder.build();
        }
        return new AnswerReason(answer).stamped(
            code >= 500 && UpstreamMiss.refused(upstream)
                ? UpstreamMiss.refusalReason(upstream) : RequestReasons.upstream(code)
        );
    }

    /**
     * The call to the upstream failed without an answer.
     * @param error Failure as caught (wrapped or not)
     * @param what What failed, for the cause record
     * @return Answer for the client
     */
    public Response failed(final Throwable error, final String what) {
        final Throwable root = new FailureCause(error).root();
        final Response result;
        if (root instanceof UpstreamStatusException status) {
            // Not a failed call: an upstream answer carried as an exception.
            result = this.answered(status.status(), status.headers());
        } else {
            final ResponseBuilder builder = ResponseBuilder.badGateway();
            if (root instanceof UpstreamCircuitOpenException open) {
                builder.header(UpstreamCircuitOpenException.HEADER, "true");
                if (open.retryAfterSeconds() > 0L) {
                    builder.header(
                        UpstreamMiss.RETRY_AFTER, Long.toString(open.retryAfterSeconds())
                    );
                }
            }
            final Response answer = builder.textBody(UpstreamMiss.UNAVAILABLE).build();
            this.cause(answer).failure(error).warnUnlessExpected(what);
            result = new AnswerReason(answer).stamped(new FailureCause(error).reason());
        }
        return result;
    }

    /**
     * Relay a local refusal with its markers.
     * @param code Status of the refusal
     * @param upstream Headers of the refusal
     * @return Answer for the client
     */
    private Response refusal(final int code, final Headers upstream) {
        final boolean bulkhead = !upstream.values(LocalRefusal.BULKHEAD).isEmpty();
        final ResponseBuilder builder = code == RsStatus.SERVICE_UNAVAILABLE.code()
            ? ResponseBuilder.unavailable() : ResponseBuilder.badGateway();
        builder.header(
            bulkhead ? LocalRefusal.BULKHEAD : UpstreamCircuitOpenException.HEADER, "true"
        );
        UpstreamMiss.retryAfter(builder, upstream);
        final Response answer = builder.textBody(UpstreamMiss.UNAVAILABLE).build();
        this.cause(answer)
            .reason(UpstreamMiss.refusalReason(upstream))
            .warnUnlessExpected("Upstream request was not sent");
        return answer;
    }

    /**
     * Cause record of a 5xx answer.
     * @param answer Answer for the client
     * @return Record to complete and emit
     */
    private ServerErrorLog cause(final Response answer) {
        return new ServerErrorLog(this.logger, "proxy_upstream", this.request)
            .repository(this.repo)
            .path(this.path)
            .status(answer.status().code());
    }

    /**
     * Whether an upstream answer is a refusal Pantera produced itself.
     * @param upstream Headers of the upstream answer
     * @return True when it carries the circuit-open or the bulkhead marker
     */
    private static boolean refused(final Headers upstream) {
        return !upstream.values(UpstreamCircuitOpenException.HEADER).isEmpty()
            || !upstream.values(LocalRefusal.BULKHEAD).isEmpty();
    }

    /**
     * Reason token of a local refusal.
     * @param upstream Headers of the refusal
     * @return Token
     */
    private static String refusalReason(final Headers upstream) {
        return upstream.values(LocalRefusal.BULKHEAD).isEmpty() ? "circuit_open" : "bulkhead_full";
    }

    /**
     * Copy the upstream's Retry-After, if it sent one.
     * @param builder Answer being built
     * @param upstream Headers of the upstream answer
     */
    private static void retryAfter(final ResponseBuilder builder, final Headers upstream) {
        final List<String> values = upstream.values(UpstreamMiss.RETRY_AFTER);
        if (!values.isEmpty() && values.get(0).length() <= 64) {
            builder.header(UpstreamMiss.RETRY_AFTER, values.get(0));
        }
    }
}
