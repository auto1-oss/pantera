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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.context.RequestContext;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * One test per row of the worked-examples table in §2 of the target
 * architecture doc — locks in the pass-through contract for
 * {@link Fault.AllProxiesFailed}.
 */
final class FaultAllProxiesFailedPassThroughTest {

    private static final RequestContext CTX =
        new RequestContext("t-1", "r-1", "npm_group", "/npm/@scope/pkg");

    private static final String GROUP = "npm_group";

    // ---------- helpers ----------

    private static Response bodied(final RsStatus status, final String body) {
        return ResponseBuilder.from(status)
            .body(new Content.From(body.getBytes(StandardCharsets.UTF_8)))
            .build();
    }

    private static Response empty(final RsStatus status) {
        return ResponseBuilder.from(status).build();
    }

    private static Fault.MemberOutcome fiveXx(final String name, final Response resp) {
        return Fault.MemberOutcome.responded(name, Fault.MemberOutcome.Kind.FIVE_XX, resp);
    }

    private static Fault.MemberOutcome notFound(final String name, final Response resp) {
        return Fault.MemberOutcome.responded(name, Fault.MemberOutcome.Kind.NOT_FOUND, resp);
    }

    private static Fault.MemberOutcome threw(final String name, final Throwable cause) {
        return Fault.MemberOutcome.threw(name, Fault.MemberOutcome.Kind.EXCEPTION, cause);
    }

    private static Fault.MemberOutcome cancelled(final String name) {
        return Fault.MemberOutcome.threw(name, Fault.MemberOutcome.Kind.CANCELLED, null);
    }

    private static Fault.AllProxiesFailed apf(final List<Fault.MemberOutcome> outcomes) {
        return new Fault.AllProxiesFailed(
            GROUP, outcomes, FaultTranslator.pickWinningFailure(outcomes)
        );
    }

    private static String bodyOf(final Response resp) throws ExecutionException, InterruptedException {
        return new String(resp.body().asBytesFuture().get(), StandardCharsets.UTF_8);
    }

    // ---------- worked examples from §2 ----------

    /** Proxy 1 → 500 + Proxy 2 → 503 ⇒ 503 body passes through. */
    @Test
    void row503BeatsRow500ByRetryability() throws Exception {
        final Response p1 = bodied(RsStatus.INTERNAL_ERROR, "500-body");
        final Response p2 = bodied(RsStatus.SERVICE_UNAVAILABLE, "503-body");
        final Response resp = FaultTranslator.translate(
            apf(List.of(fiveXx("p1", p1), fiveXx("p2", p2))), CTX
        );
        MatcherAssert.assertThat(
            "503 wins over 500 by retryability",
            resp.status(), Matchers.is(RsStatus.SERVICE_UNAVAILABLE)
        );
        MatcherAssert.assertThat(
            "winner body passes through", bodyOf(resp), Matchers.is("503-body")
        );
        MatcherAssert.assertThat(
            resp.headers().values(FaultTranslator.HEADER_FAULT),
            Matchers.contains("proxies-failed:p2")
        );
    }

    /** Proxy 1 → 502 + Proxy 2 → 500 ⇒ 502 body passes through. */
    @Test
    void row502BeatsRow500ByRetryability() throws Exception {
        final Response p1 = bodied(RsStatus.BAD_GATEWAY, "502-body");
        final Response p2 = bodied(RsStatus.INTERNAL_ERROR, "500-body");
        final Response resp = FaultTranslator.translate(
            apf(List.of(fiveXx("p1", p1), fiveXx("p2", p2))), CTX
        );
        MatcherAssert.assertThat(
            "502 wins over 500 by retryability",
            resp.status(), Matchers.is(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "winner body passes through", bodyOf(resp), Matchers.is("502-body")
        );
        MatcherAssert.assertThat(
            resp.headers().values(FaultTranslator.HEADER_FAULT),
            Matchers.contains("proxies-failed:p1")
        );
    }

    /**
     * Proxy 1 → 404 + Proxy 2 → 500 ⇒ 500 body passes through
     * (404 has no body to pass through per worked-examples note — it's only
     * considered when it's the only response, and in that case we'd never
     * construct AllProxiesFailed at all). Here the 500 is the real failure.
     */
    @Test
    void row404AndRow500ProducesThe500BodyPassingThrough() throws Exception {
        final Response p1 = empty(RsStatus.NOT_FOUND);
        final Response p2 = bodied(RsStatus.INTERNAL_ERROR, "500-body");
        final Response resp = FaultTranslator.translate(
            apf(List.of(notFound("p1", p1), fiveXx("p2", p2))), CTX
        );
        MatcherAssert.assertThat(
            "500 beats 404 by retryability (non-5xx ranks after 5xx)",
            resp.status(), Matchers.is(RsStatus.INTERNAL_ERROR)
        );
        MatcherAssert.assertThat(
            "500 body passes through", bodyOf(resp), Matchers.is("500-body")
        );
        MatcherAssert.assertThat(
            resp.headers().values(FaultTranslator.HEADER_FAULT),
            Matchers.contains("proxies-failed:p2")
        );
    }

    /** Proxy 1 → 503 (empty body) + Proxy 2 → 503 (JSON body) ⇒ Proxy 2's body wins. */
    @Test
    void sameStatusWithBodyBeatsNoBody() throws Exception {
        final Response p1 = empty(RsStatus.SERVICE_UNAVAILABLE);
        final Response p2 = bodied(RsStatus.SERVICE_UNAVAILABLE, "{\"retry\":true}");
        final Response resp = FaultTranslator.translate(
            apf(List.of(fiveXx("p1", p1), fiveXx("p2", p2))), CTX
        );
        MatcherAssert.assertThat(
            "503 in both → with-body wins",
            resp.status(), Matchers.is(RsStatus.SERVICE_UNAVAILABLE)
        );
        MatcherAssert.assertThat(
            "JSON body from p2 passes through",
            bodyOf(resp), Matchers.is("{\"retry\":true}")
        );
        MatcherAssert.assertThat(
            resp.headers().values(FaultTranslator.HEADER_FAULT),
            Matchers.contains("proxies-failed:p2")
        );
    }

    /** Proxy 1 → ConnectException + Proxy 2 → 500 ⇒ 500 body passes through. */
    @Test
    void proxyThatThrewContributesNoResponseSoOtherWins() throws Exception {
        final Response p2 = bodied(RsStatus.INTERNAL_ERROR, "500-body");
        final Response resp = FaultTranslator.translate(
            apf(List.of(
                threw("p1", new java.net.ConnectException("refused")),
                fiveXx("p2", p2)
            )), CTX
        );
        MatcherAssert.assertThat(
            "500 wins because p1 produced no response at all",
            resp.status(), Matchers.is(RsStatus.INTERNAL_ERROR)
        );
        MatcherAssert.assertThat(bodyOf(resp), Matchers.is("500-body"));
        MatcherAssert.assertThat(
            resp.headers().values(FaultTranslator.HEADER_FAULT),
            Matchers.contains("proxies-failed:p2")
        );
    }

    /** Every proxy threw/cancelled/timed out ⇒ synthesized 502 with none-responded tag. */
    @Test
    void everyProxyFailedWithoutResponseSynthesizes502NoneResponded() throws Exception {
        final Fault.AllProxiesFailed fault = apf(List.of(
            threw("p1", new java.util.concurrent.TimeoutException("slow")),
            cancelled("p2"),
            threw("p3", new RuntimeException("boom"))
        ));
        MatcherAssert.assertThat(
            "pickWinningFailure is empty when no member produced a response",
            fault.winningResponse(), Matchers.is(Optional.empty())
        );
        final Response resp = FaultTranslator.translate(fault, CTX);
        MatcherAssert.assertThat(
            "synthesized 502", resp.status(), Matchers.is(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "X-Pantera-Fault: proxies-failed:none-responded",
            resp.headers().values(FaultTranslator.HEADER_FAULT),
            Matchers.contains(FaultTranslator.TAG_PROXIES_NONE_RESPONDED)
        );
        MatcherAssert.assertThat(
            "X-Pantera-Proxies-Tried reflects members tried",
            resp.headers().values(FaultTranslator.HEADER_PROXIES_TRIED),
            Matchers.contains("3")
        );
        MatcherAssert.assertThat(
            "synthesized body is a JSON sentinel",
            bodyOf(resp), Matchers.containsString("all upstream members failed")
        );
    }

    // ---------- additional coverage for pickWinningFailure ----------

    @Test
    void declarationOrderBreaksTiesWhenStatusAndBodyAreEqual() throws Exception {
        final Response p1 = bodied(RsStatus.BAD_GATEWAY, "same");
        final Response p2 = bodied(RsStatus.BAD_GATEWAY, "same");
        final Response resp = FaultTranslator.translate(
            apf(List.of(fiveXx("p1", p1), fiveXx("p2", p2))), CTX
        );
        MatcherAssert.assertThat(
            "earliest-declared wins among full ties",
            resp.headers().values(FaultTranslator.HEADER_FAULT),
            Matchers.contains("proxies-failed:p1")
        );
    }

    @Test
    void nonFiveXxResponseRanksAfterEveryFiveXx() throws Exception {
        final Response p1 = bodied(RsStatus.NOT_FOUND, "nope");
        final Response p2 = bodied(RsStatus.GATEWAY_TIMEOUT, "gone");
        final Response resp = FaultTranslator.translate(
            apf(List.of(notFound("p1", p1), fiveXx("p2", p2))), CTX
        );
        MatcherAssert.assertThat(
            "504 beats 404",
            resp.status(), Matchers.is(RsStatus.GATEWAY_TIMEOUT)
        );
    }

    @Test
    void pickWinningFailureReturnsEmptyWhenNoMembersRespond() {
        MatcherAssert.assertThat(
            FaultTranslator.pickWinningFailure(List.of()),
            Matchers.is(Optional.empty())
        );
    }

    @Test
    void twoFiveHundredsDeclarationOrderWins() throws Exception {
        final Response p1 = bodied(RsStatus.INTERNAL_ERROR, "500-a");
        final Response p2 = bodied(RsStatus.INTERNAL_ERROR, "500-b");
        final Response resp = FaultTranslator.translate(
            apf(List.of(fiveXx("p1", p1), fiveXx("p2", p2))), CTX
        );
        MatcherAssert.assertThat(
            "two 500s — declaration order wins",
            resp.headers().values(FaultTranslator.HEADER_FAULT),
            Matchers.contains("proxies-failed:p1")
        );
    }
}
