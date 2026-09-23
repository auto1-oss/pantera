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
package com.auto1.pantera.group;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.cache.BaseCachedProxySlice;
import com.auto1.pantera.http.fault.Fault;
import com.auto1.pantera.http.fault.FaultTranslator;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Go group slice: merges {@code <module>/@v/list} across all members.
 *
 * <p>A module's version list is the union of what every member knows: the
 * hosted member's private versions and the proxy's upstream versions. The
 * generic first-wins walk served one member's list only, so {@code go list
 * -m -versions} and {@code @latest} resolution saw either the upstream or
 * the local versions, never both. Every other request goes to the
 * {@link GroupResolver} walk.
 *
 * <p>The merge honours the group-member circuit breaker exactly like the
 * walk: it uses the resolver's own members (shared
 * {@code AutoBlockRegistry} per member), sends an open-circuit member only a
 * cache-only probe, treats an upstream-circuit-open marker as a skip without
 * conviction, and records success / failure for genuine outcomes. When no
 * member contributes a list, a genuine failure answers a fault and an
 * all-unavailable group answers 503 + Retry-After without asking the members
 * again; only an all-404 result falls back to the walk.
 *
 * @since 2.2.9
 */
public final class GoGroupSlice implements Slice {

    /**
     * Suffix of the Go module proxy version-list endpoint.
     */
    private static final String LIST_SUFFIX = "/@v/list";

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.group";

    /**
     * Group resolver handling every other request.
     */
    private final Slice delegate;

    /**
     * Group repository name.
     */
    private final String group;

    /**
     * Flattened members in declared order.
     */
    private final List<MemberSlice> members;

    /**
     * Wiring constructor: shares the resolver's members, so both paths see
     * the same per-member breaker state and proxy flags.
     *
     * @param resolver Group resolver handling every other request
     */
    public GoGroupSlice(final GroupResolver resolver) {
        this(resolver, resolver.groupName(), resolver.members());
    }

    /**
     * Primary constructor.
     *
     * @param delegate Group walk handling every other request
     * @param group Group repository name
     * @param members Flattened members in declared order
     */
    GoGroupSlice(final Slice delegate, final String group, final List<MemberSlice> members) {
        this.delegate = delegate;
        this.group = group;
        this.members = List.copyOf(members);
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        if (!"GET".equals(line.method().value())
            || !line.uri().getPath().endsWith(LIST_SUFFIX)) {
            return this.delegate.response(line, headers, body);
        }
        return body.asBytesFuture().thenCompose(ignored -> this.mergeLists(line, headers));
    }

    /**
     * Ask every member for the list and answer the de-duplicated union in
     * member order.
     */
    private CompletableFuture<Response> mergeLists(final RequestLine line, final Headers headers) {
        final Headers memberHeaders = new Headers(
            headers.asList().stream()
                .filter(h -> !"X-FullPath".equalsIgnoreCase(h.getKey()))
                .toList()
        ).copy().add(new Header(EcsLoggingSlice.INTERNAL_ROUTING_HEADER, "true"));
        final List<CompletableFuture<MemberList>> lists = new ArrayList<>(this.members.size());
        for (final MemberSlice member : this.members) {
            lists.add(this.fetchList(member, line, memberHeaders));
        }
        return CompletableFuture.allOf(lists.toArray(CompletableFuture[]::new))
            .thenCompose(done -> this.answer(
                lists.stream().map(CompletableFuture::join).toList(), line, headers
            ));
    }

    /**
     * Build the group answer from every member's outcome.
     */
    private CompletableFuture<Response> answer(
        final List<MemberList> results, final RequestLine line, final Headers headers
    ) {
        final Set<String> versions = new LinkedHashSet<>();
        boolean answered = false;
        boolean failed = false;
        boolean skipped = false;
        long retryAfter = 0L;
        for (final MemberList result : results) {
            if (result.list().isPresent()) {
                answered = true;
                addVersions(versions, result.list().get());
            }
            failed |= result.outcome() == Outcome.FAILED;
            skipped |= result.outcome() == Outcome.SKIPPED;
            retryAfter = Math.max(retryAfter, result.retryAfter());
        }
        if (answered) {
            final StringBuilder out = new StringBuilder();
            versions.forEach(ver -> out.append(ver).append('\n'));
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().textBody(out.toString()).build()
            );
        }
        if (failed) {
            return CompletableFuture.completedFuture(
                FaultTranslator.translate(
                    new Fault.AllProxiesFailed(this.group, List.of(), Optional.empty()), null
                )
            );
        }
        if (skipped) {
            return CompletableFuture.completedFuture(this.unavailable(line, retryAfter));
        }
        return this.delegate.response(line, headers, Content.EMPTY);
    }

    /**
     * All members skipped (circuit open): 503 + Retry-After, never a 404
     * that could be negative-cached past the outage.
     */
    private Response unavailable(final RequestLine line, final long hint) {
        final long retry = Math.max(5L, hint);
        EcsLogger.warn(LOGGER)
            .message("All go group members circuit-open for the version list — returning 503,"
                + " Retry-After " + retry + "s")
            .eventCategory("network")
            .eventAction("group_all_members_circuit_open")
            .eventOutcome("failure")
            .field("repository.name", this.group)
            .field("url.path", line.uri().getPath())
            .field("log.source", "application")
            .log();
        return ResponseBuilder.from(RsStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", Long.toString(retry))
            .header(UpstreamCircuitOpenException.HEADER, "true")
            .textBody("All group members are temporarily unavailable (upstream circuit open)")
            .build();
    }

    /**
     * One member's version list and outcome.
     */
    private CompletableFuture<MemberList> fetchList(
        final MemberSlice member, final RequestLine line, final Headers headers
    ) {
        if (member.isCircuitOpen()) {
            return this.probeCache(member, line, headers);
        }
        final long start = System.currentTimeMillis();
        return member.slice().response(member.rewritePath(line), headers, Content.EMPTY)
            .thenCompose(resp -> resp.body().asBytesFuture().thenApply(
                bytes -> this.classify(member, line, resp, bytes, start)
            ))
            .exceptionally(err -> this.failure(member, line, err, start));
    }

    /**
     * Cache-only probe of an open-circuit member: never reaches the
     * upstream and records nothing on the member's breaker.
     */
    private CompletableFuture<MemberList> probeCache(
        final MemberSlice member, final RequestLine line, final Headers headers
    ) {
        final long hint = member.retryAfterSeconds();
        final Headers probe = headers.copy()
            .add(new Header(BaseCachedProxySlice.CACHE_ONLY_HEADER, "true"));
        return member.slice().response(member.rewritePath(line), probe, Content.EMPTY)
            .thenCompose(resp -> resp.body().asBytesFuture().thenApply(bytes -> {
                if (resp.status() == RsStatus.OK) {
                    this.metric(member, "success", -1L);
                    return new MemberList(Optional.of(bytes), Outcome.ANSWERED, 0L);
                }
                return new MemberList(Optional.empty(), Outcome.SKIPPED, hint);
            }))
            .exceptionally(err -> new MemberList(Optional.empty(), Outcome.SKIPPED, hint));
    }

    /**
     * Classify a member response the way the group walk does.
     */
    private MemberList classify(
        final MemberSlice member, final RequestLine line, final Response resp,
        final byte[] bytes, final long start
    ) {
        final RsStatus status = resp.status();
        final long latency = System.currentTimeMillis() - start;
        if (status == RsStatus.OK) {
            member.recordSuccess();
            this.metric(member, "success", latency);
            return new MemberList(Optional.of(bytes), Outcome.ANSWERED, 0L);
        }
        if (status.success() || status == RsStatus.NOT_MODIFIED
            || status == RsStatus.FORBIDDEN || GroupResolver.isCooldownVerdict(resp)) {
            member.recordSuccess();
            this.metric(member, "success", latency);
            return new MemberList(Optional.empty(), Outcome.ANSWERED, 0L);
        }
        if (status == RsStatus.NOT_FOUND || status.redirection()) {
            this.metric(member, status == RsStatus.NOT_FOUND ? "not_found" : "redirect", latency);
            return new MemberList(Optional.empty(), Outcome.ANSWERED, 0L);
        }
        if (status.serverError()
            && !resp.headers().values(UpstreamCircuitOpenException.HEADER).isEmpty()) {
            this.metric(member, "circuit_open", -1L);
            return new MemberList(
                Optional.empty(), Outcome.SKIPPED, GroupResolver.parseRetryAfterSeconds(resp)
            );
        }
        member.recordFailure();
        this.metric(member, "error", latency);
        EcsLogger.warn(LOGGER)
            .message("Go group member failed to answer the version list: " + member.name())
            .eventCategory("web")
            .eventAction("group_go_list_member_failed")
            .eventOutcome("failure")
            .field("repository.name", this.group)
            .field("url.path", line.uri().getPath())
            .field("http.response.status_code", status.code())
            .field("log.source", "application")
            .log();
        return new MemberList(Optional.empty(), Outcome.FAILED, 0L);
    }

    /**
     * A member call that threw: a failure unless it was cancelled.
     */
    private MemberList failure(
        final MemberSlice member, final RequestLine line, final Throwable err, final long start
    ) {
        final Throwable cause = err.getCause() != null ? err.getCause() : err;
        if (cause instanceof CancellationException) {
            return new MemberList(Optional.empty(), Outcome.ANSWERED, 0L);
        }
        member.recordFailure();
        this.metric(member, "error", System.currentTimeMillis() - start);
        EcsLogger.warn(LOGGER)
            .message("Go group member failed to answer the version list: " + member.name())
            .eventCategory("web")
            .eventAction("group_go_list_member_failed")
            .eventOutcome("failure")
            .field("repository.name", this.group)
            .field("url.path", line.uri().getPath())
            .error(cause)
            .field("log.source", "application")
            .log();
        return new MemberList(Optional.empty(), Outcome.FAILED, 0L);
    }

    /**
     * Record the per-member group metric (same series as the walk).
     */
    private void metric(final MemberSlice member, final String result, final long latencyMs) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordGroupMemberRequest(this.group, member.name(), result);
            if (latencyMs >= 0L) {
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordGroupMemberLatency(this.group, member.name(), result, latencyMs);
            }
        }
    }

    private static void addVersions(final Set<String> versions, final byte[] bytes) {
        for (final String ver : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            if (!ver.isBlank()) {
                versions.add(ver.trim());
            }
        }
    }

    /**
     * How a member took part in the merge.
     */
    private enum Outcome {
        /** Answered (with or without a list). */
        ANSWERED,
        /** Genuinely failed; recorded on its breaker. */
        FAILED,
        /** Skipped: its group or upstream circuit is open. */
        SKIPPED
    }

    /**
     * One member's contribution.
     *
     * @param list Version list bytes when the member answered 200
     * @param outcome How the member took part
     * @param retryAfter Retry-After hint in seconds for a skipped member
     */
    private record MemberList(Optional<byte[]> list, Outcome outcome, long retryAfter) {
    }
}
