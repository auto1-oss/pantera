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
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.fault.FaultTranslator;
import com.auto1.pantera.http.log.AnswerReason;
import com.auto1.pantera.http.log.RequestReasons;
import com.auto1.pantera.http.log.RequestTrace;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ReasonSlice}.
 *
 * @since 2.2.12
 */
final class ReasonSliceTest {

    @Test
    void stampsTheReasonNotedWhileTheRequestWasHandled() throws Exception {
        final Slice origin = (line, headers, body) -> {
            RequestReasons.instance().note(headers, "negative_cache");
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        };
        MatcherAssert.assertThat(
            ReasonSliceTest.reason(origin), new IsEqual<>(Optional.of("negative_cache"))
        );
    }

    @Test
    void namesTheStatusWhenNothingExplainedIt() throws Exception {
        final Slice origin = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        MatcherAssert.assertThat(
            ReasonSliceTest.reason(origin), new IsEqual<>(Optional.of("not_found"))
        );
    }

    @Test
    void leavesASuccessfulAnswerAlone() throws Exception {
        final Slice origin = (line, headers, body) -> {
            RequestReasons.instance().note(headers, "connection_closed");
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        };
        MatcherAssert.assertThat(ReasonSliceTest.reason(origin), new IsEqual<>(Optional.empty()));
    }

    @Test
    void aNotModifiedAnswerNeedsNoReason() throws Exception {
        final Slice origin = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.from(RsStatus.NOT_MODIFIED).build()
        );
        MatcherAssert.assertThat(ReasonSliceTest.reason(origin), new IsEqual<>(Optional.empty()));
    }

    @Test
    void aGroupNotFoundIsCachedIfOneMemberAnsweredFromItsCache() throws Exception {
        // The group relays the 404 of its last member, which the upstream
        // gave; an earlier member answered from its negative cache.
        final Slice group = (line, headers, body) -> {
            RequestReasons.instance().note(headers, "negative_cache");
            RequestReasons.instance().note(headers, "upstream_status_404");
            return CompletableFuture.completedFuture(
                new AnswerReason(ResponseBuilder.notFound().build()).stamped("upstream_status_404")
            );
        };
        MatcherAssert.assertThat(
            ReasonSliceTest.reason(group), new IsEqual<>(Optional.of("negative_cache"))
        );
    }

    @Test
    void aRelayedForbiddenKeepsTheReasonItCameWith() throws Exception {
        // One member lost its connection, the next one answered 403, which
        // ends the walk: the 403 is that member's, not the lost connection's.
        final Slice group = (line, headers, body) -> {
            RequestReasons.instance().note(headers, "connection_closed");
            return CompletableFuture.completedFuture(
                new AnswerReason(ResponseBuilder.forbidden().build()).stamped("upstream_status_403")
            );
        };
        MatcherAssert.assertThat(
            ReasonSliceTest.reason(group), new IsEqual<>(Optional.of("upstream_status_403"))
        );
    }

    @Test
    void aRelayedForbiddenIsNotExplainedByAnotherMember() throws Exception {
        // As above, but the member that answered 403 gave no reason beyond
        // the status: the lost connection of the other member still does not
        // explain it.
        final Slice group = (line, headers, body) -> {
            RequestReasons.instance().note(headers, "connection_closed");
            return CompletableFuture.completedFuture(
                new AnswerReason(ResponseBuilder.forbidden().build()).stamped("forbidden")
            );
        };
        MatcherAssert.assertThat(
            ReasonSliceTest.reason(group), new IsEqual<>(Optional.of("forbidden"))
        );
    }

    @Test
    void aServerErrorIsExplainedByTheFailureBehindIt() throws Exception {
        final Slice proxy = (line, headers, body) -> {
            RequestReasons.instance().note(headers, "upstream_status_404");
            RequestReasons.instance().note(headers, "read_timeout");
            return CompletableFuture.completedFuture(ResponseBuilder.badGateway().build());
        };
        MatcherAssert.assertThat(
            ReasonSliceTest.reason(proxy), new IsEqual<>(Optional.of("read_timeout"))
        );
    }

    @Test
    void readsTheMarkerOfAnAnswerNobodyExplained() throws Exception {
        MatcherAssert.assertThat(
            "a full bulkhead",
            ReasonSliceTest.reason(
                ReasonSliceTest.answering(
                    ResponseBuilder.unavailable().header(LocalRefusal.BULKHEAD, "true").build()
                )
            ),
            new IsEqual<>(Optional.of("bulkhead_full"))
        );
        MatcherAssert.assertThat(
            "an open circuit breaker",
            ReasonSliceTest.reason(
                ReasonSliceTest.answering(
                    ResponseBuilder.badGateway()
                        .header(UpstreamCircuitOpenException.HEADER, "true").build()
                )
            ),
            new IsEqual<>(Optional.of("circuit_open"))
        );
        MatcherAssert.assertThat(
            "a cooldown block",
            ReasonSliceTest.reason(
                ReasonSliceTest.answering(
                    ResponseBuilder.forbidden()
                        .header(CooldownResponseFactory.HEADER, "blocked").build()
                )
            ),
            new IsEqual<>(Optional.of("cooldown_active"))
        );
        MatcherAssert.assertThat(
            "a translated fault",
            ReasonSliceTest.reason(
                ReasonSliceTest.answering(
                    ResponseBuilder.internalError()
                        .header(FaultTranslator.HEADER_FAULT, "index-unavailable").build()
                )
            ),
            new IsEqual<>(Optional.of("index_unavailable"))
        );
    }

    @Test
    void aStatusNameOfOneAnswerDoesNotExplainAnother() throws Exception {
        // A group skipped its only member, whose breaker is open, after the
        // member's cache had nothing: the member's bare "not found" says
        // nothing about the 503 the group then answers.
        final Slice group = (line, headers, body) -> {
            RequestReasons.instance().note(headers, "not_found");
            return CompletableFuture.completedFuture(
                ResponseBuilder.unavailable()
                    .header(UpstreamCircuitOpenException.HEADER, "true").build()
            );
        };
        MatcherAssert.assertThat(
            ReasonSliceTest.reason(group), new IsEqual<>(Optional.of("circuit_open"))
        );
    }

    @Test
    void aRelayedAnswerNamedByAnotherStatusIsRenamed() throws Exception {
        // A layer above turned a member's 404 into a 502: the 404's name
        // must not stay on it.
        final Slice origin = (line, headers, body) -> CompletableFuture.completedFuture(
            new AnswerReason(ResponseBuilder.badGateway().build()).stamped("not_found")
        );
        MatcherAssert.assertThat(
            ReasonSliceTest.reason(origin), new IsEqual<>(Optional.of("bad_gateway"))
        );
    }

    @Test
    void givesEveryCallItsOwnSlot() throws Exception {
        // A group calls its member through the member's own ReasonSlice:
        // what the member notes must not land in the group's slot.
        final Slice member = new ReasonSlice(
            (line, headers, body) -> {
                RequestReasons.instance().note(headers, "negative_cache");
                return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
            }
        );
        final Slice group = (line, headers, body) ->
            member.response(line, headers, body).thenApply(
                ignored -> ResponseBuilder.unavailable().build()
            );
        MatcherAssert.assertThat(
            ReasonSliceTest.reason(group), new IsEqual<>(Optional.of("service_unavailable"))
        );
    }

    @Test
    void replacesASlotTheRequestAlreadyCarries() throws Exception {
        final AtomicReference<List<String>> seen = new AtomicReference<>();
        final Slice origin = (line, headers, body) -> {
            seen.set(headers.values(RequestReasons.SLOT_HEADER));
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        };
        new ReasonSlice(origin).response(
            new RequestLine(RqMethod.GET, "/a"),
            Headers.from(RequestReasons.SLOT_HEADER, "forged"), Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            seen.get().size() == 1 && !"forged".equals(seen.get().get(0)), new IsEqual<>(true)
        );
    }

    @Test
    void passesAFailureOfTheOriginOn() {
        final Slice origin = (line, headers, body) ->
            CompletableFuture.failedFuture(new IllegalStateException("boom"));
        final ExecutionException thrown = Assertions.assertThrows(
            ExecutionException.class,
            () -> new ReasonSlice(origin).response(
                new RequestLine(RqMethod.GET, "/a"), Headers.EMPTY, Content.EMPTY
            ).get(10, TimeUnit.SECONDS)
        );
        MatcherAssert.assertThat(thrown.getCause().getMessage(), new IsEqual<>("boom"));
    }

    @Test
    void recordsItsAnswerInTheTraceOfATracedRequest() throws Exception {
        final RequestTrace traces = RequestTrace.instance();
        final String trace = traces.open();
        final Slice member = new ReasonSlice(
            (line, headers, body) -> {
                RequestReasons.instance().note(headers, "negative_cache");
                return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
            },
            "maven-central", "maven-proxy"
        );
        final Slice group = new ReasonSlice(
            (line, headers, body) -> member.response(line, headers, body),
            "libs-release", "maven-group"
        );
        group.response(
            new RequestLine(RqMethod.GET, "/a"),
            Headers.from(RequestTrace.HEADER, trace), Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        final List<RequestTrace.Step> steps = traces.close(trace);
        MatcherAssert.assertThat(
            "the member answered first, then the group",
            steps.stream().map(RequestTrace.Step::repository).toList(),
            new IsEqual<>(List.of("maven-central", "libs-release"))
        );
        MatcherAssert.assertThat(
            "each step has the type, the status and the reason",
            steps.stream()
                .map(step -> step.type() + " " + step.status() + " " + step.reason()).toList(),
            new IsEqual<>(
                List.of("maven-proxy 404 negative_cache", "maven-group 404 negative_cache")
            )
        );
    }

    @Test
    void recordsASuccessfulAnswerWithoutAReason() throws Exception {
        final RequestTrace traces = RequestTrace.instance();
        final String trace = traces.open();
        new ReasonSlice(
            (line, headers, body) ->
                CompletableFuture.completedFuture(ResponseBuilder.ok().build()),
            "files", "file"
        ).response(
            new RequestLine(RqMethod.GET, "/a"),
            Headers.from(RequestTrace.HEADER, trace), Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        final List<RequestTrace.Step> steps = traces.close(trace);
        MatcherAssert.assertThat(
            steps.size() == 1 && steps.get(0).status() == 200 && steps.get(0).reason() == null,
            new IsEqual<>(true)
        );
    }

    @Test
    void recordsARepositoryThatFailedWithoutAnAnswer() throws Exception {
        final RequestTrace traces = RequestTrace.instance();
        final String trace = traces.open();
        new ReasonSlice(
            (line, headers, body) ->
                CompletableFuture.failedFuture(new java.net.ConnectException("Connection refused")),
            "broken", "maven-proxy"
        ).response(
            new RequestLine(RqMethod.GET, "/a"),
            Headers.from(RequestTrace.HEADER, trace), Content.EMPTY
        ).handle((response, error) -> null).get(10, TimeUnit.SECONDS);
        final List<RequestTrace.Step> steps = traces.close(trace);
        MatcherAssert.assertThat(
            steps.stream().map(step -> step.status() + " " + step.reason()).toList(),
            new IsEqual<>(List.of("0 connection_refused"))
        );
    }

    /**
     * A slice answering with a fixed response.
     * @param response Response
     * @return Slice
     */
    private static Slice answering(final Response response) {
        return (line, headers, body) -> CompletableFuture.completedFuture(response);
    }

    /**
     * The reason a slice's answer carries when served through a reason slice.
     * @param origin Slice
     * @return Reason
     * @throws Exception On error
     */
    private static Optional<String> reason(final Slice origin) throws Exception {
        return new AnswerReason(
            new ReasonSlice(origin).response(
                new RequestLine(RqMethod.GET, "/a"), Headers.EMPTY, Content.EMPTY
            ).get(10, TimeUnit.SECONDS)
        ).token();
    }
}
