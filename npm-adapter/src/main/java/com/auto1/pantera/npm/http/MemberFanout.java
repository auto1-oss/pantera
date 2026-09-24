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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import com.auto1.pantera.http.rq.RequestLine;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;

/**
 * Sends one read request to every member of an npm group in parallel and
 * merges the members' JSON object answers, for group endpoints whose
 * answer is the union of all members' answers (signing keys, search) rather
 * than the first member's.
 *
 * <p>Each member either <em>answers</em> (2xx JSON object), <em>declines</em>
 * (any other non-5xx status) or <em>fails</em> (exception, timeout, 5xx
 * including the circuit-open 502, or a 2xx body that is not a JSON object).
 * Only answers are merged. When no member answered and at least one failed,
 * the group answers 503 with {@code Retry-After} instead of an empty success,
 * because an empty 200 would be cached and read by clients as "no keys" /
 * "no results" for the length of the outage.</p>
 *
 * <p>Members are addressed through their own repository slice, so the path
 * is prefixed with the member name and the caller's credentials are
 * forwarded for the member to authorize. A member response is always
 * drained, including one that arrives after its timeout.</p>
 *
 * @since 2.2.9
 */
final class MemberFanout {

    /**
     * Default per-member answer timeout.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * Smallest Retry-After the group answers with, seconds.
     */
    private static final long MIN_RETRY = 5L;

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.npm";

    /**
     * Group repository name.
     */
    private final String group;

    /**
     * Member repository names, in declared order.
     */
    private final List<String> names;

    /**
     * Member repository slices, same order as the names.
     */
    private final List<Slice> slices;

    /**
     * Per-member answer timeout.
     */
    private final Duration timeout;

    /**
     * Ctor.
     * @param group Group repository name
     * @param names Member repository names
     * @param slices Member repository slices, same order
     */
    MemberFanout(final String group, final List<String> names, final List<Slice> slices) {
        this(group, names, slices, MemberFanout.TIMEOUT);
    }

    /**
     * Ctor.
     * @param group Group repository name
     * @param names Member repository names
     * @param slices Member repository slices, same order
     * @param timeout Per-member answer timeout
     */
    MemberFanout(
        final String group, final List<String> names, final List<Slice> slices,
        final Duration timeout
    ) {
        if (names.size() != slices.size()) {
            throw new IllegalArgumentException(
                String.format(
                    "Member names and slices must have same size: %d vs %d",
                    names.size(), slices.size()
                )
            );
        }
        this.group = group;
        this.names = List.copyOf(names);
        this.slices = List.copyOf(slices);
        this.timeout = timeout;
    }

    /**
     * Query every member and merge the answers.
     * @param line Request line as received by the group
     * @param headers Request headers
     * @param merge Builds the group response from the answering members'
     *  JSON objects, in member order
     * @return Merged response, or 503 when no member answered and one failed
     */
    CompletableFuture<Response> merge(
        final RequestLine line, final Headers headers,
        final Function<List<JsonObject>, Response> merge
    ) {
        final List<CompletableFuture<Outcome>> outcomes = new ArrayList<>(this.names.size());
        for (int idx = 0; idx < this.names.size(); ++idx) {
            outcomes.add(this.member(this.names.get(idx), this.slices.get(idx), line, headers));
        }
        return CompletableFuture.allOf(outcomes.toArray(new CompletableFuture<?>[0])).thenApply(
            nothing -> {
                final List<JsonObject> answers = new ArrayList<>(outcomes.size());
                boolean failed = false;
                long retry = 0L;
                for (final CompletableFuture<Outcome> future : outcomes) {
                    final Outcome outcome = future.join();
                    outcome.json.ifPresent(answers::add);
                    failed = failed || outcome.failed;
                    retry = Math.max(retry, outcome.retry);
                }
                final Response response;
                if (answers.isEmpty() && failed) {
                    response = this.unavailable(line, headers, retry);
                } else {
                    response = merge.apply(answers);
                }
                return response;
            }
        );
    }

    /**
     * Query one member; never fails. The member response is drained by a
     * chain that does not depend on the timeout, so a response arriving
     * after the timeout is still consumed.
     * @param name Member name
     * @param slice Member slice
     * @param line Group request line
     * @param headers Request headers
     * @return Member outcome
     */
    private CompletableFuture<Outcome> member(
        final String name, final Slice slice, final RequestLine line, final Headers headers
    ) {
        final CompletableFuture<Outcome> result = new CompletableFuture<>();
        CompletableFuture<Response> raw;
        try {
            raw = slice.response(
                MemberFanout.rewrite(line, name), MemberFanout.forward(headers), Content.EMPTY
            );
        } catch (final RuntimeException err) {
            raw = CompletableFuture.failedFuture(err);
        }
        raw.thenCompose(MemberFanout::outcome).whenComplete(
            (outcome, err) -> {
                if (err == null) {
                    result.complete(outcome);
                } else {
                    result.completeExceptionally(err);
                }
            }
        );
        return result
            .orTimeout(this.timeout.toMillis(), TimeUnit.MILLISECONDS)
            .exceptionally(err -> MemberFanout.failed(name, line, err))
            .thenApply(outcome -> MemberFanout.logged(name, line, outcome));
    }

    /**
     * Classify a member response, draining the body.
     * @param response Member response
     * @return Outcome
     */
    private static CompletableFuture<Outcome> outcome(final Response response) {
        final RsStatus status = response.status();
        final long retry = MemberFanout.retryAfter(response.headers());
        return response.body().asBytesFuture().thenApply(
            bytes -> {
                final Outcome outcome;
                if (status.success()) {
                    outcome = MemberFanout.parse(bytes)
                        .map(json -> new Outcome(Optional.of(json), false, 0L, null))
                        .orElseGet(
                            () -> new Outcome(
                                Optional.empty(), true, 0L,
                                "member answered " + status.code() + " without a JSON object"
                            )
                        );
                } else if (status.serverError()) {
                    outcome = new Outcome(
                        Optional.empty(), true, retry,
                        "member answered " + status.code()
                    );
                } else {
                    outcome = new Outcome(Optional.empty(), false, 0L, null);
                }
                return outcome;
            }
        );
    }

    /**
     * Parse a body as a JSON object.
     * @param bytes Body
     * @return JSON object, if the body is one
     */
    private static Optional<JsonObject> parse(final byte[] bytes) {
        Optional<JsonObject> json;
        try (JsonReader reader = Json.createReader(
            new StringReader(new String(bytes, StandardCharsets.UTF_8))
        )) {
            json = Optional.of(reader.readObject());
        } catch (final JsonException | IllegalStateException err) {
            json = Optional.empty();
        }
        return json;
    }

    /**
     * Delta-seconds {@code Retry-After} of a member response, or 0.
     * @param headers Member response headers
     * @return Seconds
     */
    private static long retryAfter(final Headers headers) {
        long retry = 0L;
        final List<String> values = headers.values("Retry-After");
        if (!values.isEmpty()) {
            try {
                retry = Long.parseLong(values.get(0).trim());
            } catch (final NumberFormatException ignored) {
                retry = 0L;
            }
        }
        return retry;
    }

    /**
     * Log a member failure (exception or timeout).
     * @param name Member name
     * @param line Group request line
     * @param err Failure
     * @return Failed outcome, carrying the circuit-open retry hint if any
     */
    private static Outcome failed(
        final String name, final RequestLine line, final Throwable err
    ) {
        Throwable cause = err;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        long retry = 0L;
        if (cause instanceof UpstreamCircuitOpenException) {
            retry = ((UpstreamCircuitOpenException) cause).retryAfterSeconds();
        }
        EcsLogger.warn(MemberFanout.LOGGER)
            .message("npm group member left out of the merged answer: " + name)
            .eventCategory("web")
            .eventAction("group_member_merge")
            .eventOutcome("failure")
            .field("repository.name", name)
            .field("url.path", line.uri().getPath())
            .error(cause)
            .field("log.source", "application")
            .log();
        return new Outcome(Optional.empty(), true, retry, null);
    }

    /**
     * Log a member that answered with a failure status.
     * @param name Member name
     * @param line Group request line
     * @param outcome Outcome
     * @return The same outcome
     */
    private static Outcome logged(final String name, final RequestLine line, final Outcome outcome) {
        if (outcome.reason != null) {
            EcsLogger.warn(MemberFanout.LOGGER)
                .message(
                    "npm group member left out of the merged answer: " + name
                        + " (" + outcome.reason + ")"
                )
                .eventCategory("web")
                .eventAction("group_member_merge")
                .eventOutcome("failure")
                .field("repository.name", name)
                .field("url.path", line.uri().getPath())
                .field("log.source", "application")
                .log();
        }
        return outcome;
    }

    /**
     * Group answer when no member answered and at least one failed: 503 with
     * Retry-After, never an empty success.
     * @param line Group request line
     * @param headers Group request headers (carry the request's trace id)
     * @param hint Largest member Retry-After hint, seconds
     * @return Response
     */
    private Response unavailable(final RequestLine line, final Headers headers, final long hint) {
        final long retry = Math.max(MemberFanout.MIN_RETRY, hint);
        // Runs on whichever thread completed the last member: restore the
        // request's correlation fields before logging.
        RequestContextHeaders.bindToMdc(headers);
        EcsLogger.warn(MemberFanout.LOGGER)
            .message(
                "All npm group members unavailable for a merged endpoint, returning 503, "
                    + "Retry-After " + retry + "s"
            )
            .eventCategory("network")
            .eventAction("group_all_members_unavailable")
            .eventOutcome("failure")
            .field("repository.name", this.group)
            .field("trace.id", MemberFanout.traceId(headers))
            .field("url.path", line.uri().getPath())
            .field("http.response.status_code", RsStatus.SERVICE_UNAVAILABLE.code())
            .field("log.source", "application")
            .log();
        return ResponseBuilder.from(RsStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", Long.toString(retry))
            .jsonBody(
                Json.createObjectBuilder()
                    .add("error", "All group members are temporarily unavailable")
                    .build()
            )
            .build();
    }

    /**
     * Trace id of the request, from the internal context header.
     * @param headers Request headers
     * @return Trace id, or null when absent
     */
    private static String traceId(final Headers headers) {
        return headers.find(EcsLoggingSlice.CTX_TRACE_ID_HEADER).stream()
            .findFirst().map(header -> header.getValue()).orElse(null);
    }

    /**
     * Prefix the path with the member repository name, which the member's
     * path-trimming slice expects.
     * @param original Group request line
     * @param member Member name
     * @return Member request line
     */
    private static RequestLine rewrite(final RequestLine original, final String member) {
        final URI uri = original.uri();
        final String raw = uri.getRawPath();
        final StringBuilder full = new StringBuilder("/").append(member);
        if (!raw.startsWith("/")) {
            full.append('/');
        }
        full.append(raw);
        if (uri.getRawQuery() != null) {
            full.append('?').append(uri.getRawQuery());
        }
        return new RequestLine(original.method().value(), full.toString(), original.version());
    }

    /**
     * Drop the internal full-path header, which names the group path.
     * @param headers Request headers
     * @return Headers to forward
     */
    private static Headers forward(final Headers headers) {
        return new Headers(
            headers.asList().stream()
                .filter(hdr -> !"X-FullPath".equalsIgnoreCase(hdr.getKey()))
                .toList()
        );
    }

    /**
     * What one member contributed.
     * @since 2.2.9
     */
    private static final class Outcome {

        /**
         * JSON object answer, if the member answered.
         */
        private final Optional<JsonObject> json;

        /**
         * Whether the member failed (as opposed to answering or declining).
         */
        private final boolean failed;

        /**
         * Member Retry-After hint, seconds.
         */
        private final long retry;

        /**
         * Failure reason to log, or null.
         */
        private final String reason;

        /**
         * Ctor.
         * @param json JSON answer
         * @param failed Whether the member failed
         * @param retry Retry-After hint
         * @param reason Failure reason to log, or null
         */
        Outcome(
            final Optional<JsonObject> json, final boolean failed,
            final long retry, final String reason
        ) {
            this.json = json;
            this.failed = failed;
            this.retry = retry;
            this.reason = reason;
        }
    }
}
