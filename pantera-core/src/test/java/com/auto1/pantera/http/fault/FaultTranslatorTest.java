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

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Tests for {@link FaultTranslator#translate(Fault, RequestContext)} — one test
 * per {@link Fault} variant plus an exhaustive pattern-match guard that keeps
 * the implementation honest as new variants land.
 */
final class FaultTranslatorTest {

    /** Reusable request context; FaultTranslator reads nothing from it today. */
    private static final RequestContext CTX =
        new RequestContext("trace-1", "req-1", "npm_group", "/npm/@scope/pkg");

    @Test
    void notFoundMapsTo404() {
        final Response resp = FaultTranslator.translate(
            new Fault.NotFound("npm_group", "@scope/pkg", "1.0.0"), CTX
        );
        MatcherAssert.assertThat(
            "404 status", resp.status(), Matchers.is(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "no fault header on clean 404",
            resp.headers().values(FaultTranslator.HEADER_FAULT),
            Matchers.empty()
        );
    }

    @Test
    void forbiddenMapsTo403() {
        final Response resp = FaultTranslator.translate(
            new Fault.Forbidden("cooldown"), CTX
        );
        MatcherAssert.assertThat(
            "403 status", resp.status(), Matchers.is(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "no fault header on 403",
            resp.headers().values(FaultTranslator.HEADER_FAULT),
            Matchers.empty()
        );
    }

    @Test
    void indexUnavailableMapsTo500WithTag() {
        final Response resp = FaultTranslator.translate(
            new Fault.IndexUnavailable(new RuntimeException("timeout"), "SELECT …"),
            CTX
        );
        MatcherAssert.assertThat(
            "500 status", resp.status(), Matchers.is(RsStatus.INTERNAL_ERROR)
        );
        MatcherAssert.assertThat(
            "X-Pantera-Fault carries index-unavailable tag",
            resp.headers().values(FaultTranslator.HEADER_FAULT),
            Matchers.contains(FaultTranslator.TAG_INDEX)
        );
    }

    @Test
    void storageUnavailableMapsTo500WithTag() {
        final Response resp = FaultTranslator.translate(
            new Fault.StorageUnavailable(new RuntimeException("io"), "meta"), CTX
        );
        MatcherAssert.assertThat(
            "500 status", resp.status(), Matchers.is(RsStatus.INTERNAL_ERROR)
        );
        MatcherAssert.assertThat(
            "X-Pantera-Fault carries storage-unavailable tag",
            resp.headers().values(FaultTranslator.HEADER_FAULT),
            Matchers.contains(FaultTranslator.TAG_STORAGE)
        );
    }

    @Test
    void internalMapsTo500WithTag() {
        final Response resp = FaultTranslator.translate(
            new Fault.Internal(new RuntimeException("bug"), "slice"), CTX
        );
        MatcherAssert.assertThat(
            "500 status", resp.status(), Matchers.is(RsStatus.INTERNAL_ERROR)
        );
        MatcherAssert.assertThat(
            "X-Pantera-Fault carries internal tag",
            resp.headers().values(FaultTranslator.HEADER_FAULT),
            Matchers.contains(FaultTranslator.TAG_INTERNAL)
        );
    }

    @Test
    void deadlineMapsTo504WithTag() {
        final Response resp = FaultTranslator.translate(
            new Fault.Deadline(Duration.ofSeconds(5), "proxy-fanout"), CTX
        );
        MatcherAssert.assertThat(
            "504 status", resp.status(), Matchers.is(RsStatus.GATEWAY_TIMEOUT)
        );
        MatcherAssert.assertThat(
            "X-Pantera-Fault carries deadline-exceeded tag",
            resp.headers().values(FaultTranslator.HEADER_FAULT),
            Matchers.contains(FaultTranslator.TAG_DEADLINE)
        );
    }

    @Test
    void overloadMapsTo503WithRetryAfterAndTag() {
        final Response resp = FaultTranslator.translate(
            new Fault.Overload("event-queue", Duration.ofSeconds(3)), CTX
        );
        MatcherAssert.assertThat(
            "503 status", resp.status(), Matchers.is(RsStatus.SERVICE_UNAVAILABLE)
        );
        MatcherAssert.assertThat(
            "Retry-After in seconds",
            resp.headers().values("Retry-After"),
            Matchers.contains("3")
        );
        MatcherAssert.assertThat(
            "X-Pantera-Fault carries overload:<resource> tag",
            resp.headers().values(FaultTranslator.HEADER_FAULT),
            Matchers.contains("overload:event-queue")
        );
    }

    @Test
    void allProxiesFailedWithWinningResponsePassesThrough() {
        final Response upstream = ResponseBuilder.from(RsStatus.BAD_GATEWAY)
            .header("X-Upstream", "npmjs.org")
            .body(new Content.From("{\"upstream\":\"bye\"}".getBytes()))
            .build();
        final Fault.AllProxiesFailed apf = new Fault.AllProxiesFailed(
            "npm_group",
            List.of(
                Fault.MemberOutcome.responded(
                    "npm_proxy_a", Fault.MemberOutcome.Kind.FIVE_XX, upstream
                ),
                Fault.MemberOutcome.threw(
                    "npm_proxy_b", Fault.MemberOutcome.Kind.EXCEPTION,
                    new RuntimeException("boom")
                )
            ),
            Optional.of(new Fault.AllProxiesFailed.ProxyFailure("npm_proxy_a", upstream))
        );
        final Response resp = FaultTranslator.translate(apf, CTX);
        MatcherAssert.assertThat(
            "upstream status passed through",
            resp.status(), Matchers.is(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "upstream header preserved",
            resp.headers().values("X-Upstream"), Matchers.contains("npmjs.org")
        );
        MatcherAssert.assertThat(
            "X-Pantera-Fault carries proxies-failed:<member>",
            resp.headers().values(FaultTranslator.HEADER_FAULT),
            Matchers.contains("proxies-failed:npm_proxy_a")
        );
        MatcherAssert.assertThat(
            "X-Pantera-Proxies-Tried matches outcomes size",
            resp.headers().values(FaultTranslator.HEADER_PROXIES_TRIED),
            Matchers.contains("2")
        );
    }

    @Test
    void allProxiesFailedWithNoResponderSynthesizes502() {
        final Fault.AllProxiesFailed apf = new Fault.AllProxiesFailed(
            "npm_group",
            List.of(
                Fault.MemberOutcome.threw(
                    "npm_proxy_a", Fault.MemberOutcome.Kind.EXCEPTION,
                    new RuntimeException("connect refused")
                ),
                Fault.MemberOutcome.threw(
                    "npm_proxy_b", Fault.MemberOutcome.Kind.CANCELLED, null
                )
            ),
            Optional.empty()
        );
        final Response resp = FaultTranslator.translate(apf, CTX);
        MatcherAssert.assertThat(
            "synthesized 502",
            resp.status(), Matchers.is(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "X-Pantera-Fault: proxies-failed:none-responded",
            resp.headers().values(FaultTranslator.HEADER_FAULT),
            Matchers.contains(FaultTranslator.TAG_PROXIES_NONE_RESPONDED)
        );
        MatcherAssert.assertThat(
            "X-Pantera-Proxies-Tried reflects members tried",
            resp.headers().values(FaultTranslator.HEADER_PROXIES_TRIED),
            Matchers.contains("2")
        );
    }

    @Test
    void upstreamIntegrityMapsTo502WithAlgoTag() {
        final Response resp = FaultTranslator.translate(
            new Fault.UpstreamIntegrity(
                "https://maven.example/oss-parent-58.pom",
                Fault.ChecksumAlgo.SHA1,
                "15ce8a2c447057a4cfffd7a1d57b80937d293e7a",
                "0ed9e5d9e7cad24fce51b18455e0cf5ccd2c94b6"
            ),
            CTX
        );
        MatcherAssert.assertThat(
            "502 status", resp.status(), Matchers.is(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "X-Pantera-Fault carries upstream-integrity:<algo>",
            resp.headers().values(FaultTranslator.HEADER_FAULT),
            Matchers.contains("upstream-integrity:SHA1")
        );
    }

    /**
     * Exhaustive pattern-match guard. If a new {@link Fault} variant is
     * introduced without updating this switch, the Java compiler will reject
     * this file — proving that every variant has an explicit branch.
     *
     * <p>Using a switch <b>expression</b> forces exhaustiveness at compile time
     * (sealed interface + returning {@code Void}).
     */
    @Test
    void exhaustiveSwitchCompilesForEveryVariant() {
        final List<Fault> variants = List.of(
            new Fault.NotFound("s", "a", "v"),
            new Fault.Forbidden("r"),
            new Fault.IndexUnavailable(new RuntimeException(), "q"),
            new Fault.StorageUnavailable(new RuntimeException(), "k"),
            new Fault.AllProxiesFailed("g", List.of(), Optional.empty()),
            new Fault.Internal(new RuntimeException(), "w"),
            new Fault.Deadline(Duration.ZERO, "w"),
            new Fault.Overload("r", Duration.ZERO),
            new Fault.UpstreamIntegrity("u", Fault.ChecksumAlgo.SHA256, "a", "b")
        );
        for (final Fault variant : variants) {
            // Exhaustive switch expression — compiler rejects if any variant is missing.
            final Void ignored = switch (variant) {
                case Fault.NotFound nf -> null;
                case Fault.Forbidden fb -> null;
                case Fault.IndexUnavailable iu -> null;
                case Fault.StorageUnavailable su -> null;
                case Fault.AllProxiesFailed apf -> null;
                case Fault.Internal in -> null;
                case Fault.Deadline dl -> null;
                case Fault.Overload ov -> null;
                case Fault.UpstreamIntegrity ui -> null;
            };
            MatcherAssert.assertThat(
                "every variant round-trips through FaultTranslator.translate()",
                FaultTranslator.translate(variant, CTX),
                Matchers.notNullValue()
            );
        }
    }
}
