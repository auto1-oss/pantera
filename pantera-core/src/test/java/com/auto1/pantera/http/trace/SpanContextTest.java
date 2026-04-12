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
package com.auto1.pantera.http.trace;

import com.auto1.pantera.http.Headers;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SpanContext}.
 *
 * Verifies B3 single, B3 multi, W3C traceparent, X-Trace-Id, X-Request-Id
 * extraction and SRE2042 malformed-ID regeneration.
 *
 * @since 2.1.0
 */
final class SpanContextTest {

    private static final String HEX16 = "[\\da-f]{16}";

    // ---- b3 single header ----

    @Test
    void parsesB3SingleHeader() {
        final Headers hdrs = Headers.from("b3", "463ac35c9f6413ad-0020000000000001-1");
        final SpanContext ctx = SpanContext.extract(hdrs);
        MatcherAssert.assertThat(ctx.traceId(), Matchers.equalTo("463ac35c9f6413ad"));
        MatcherAssert.assertThat(ctx.parentSpanId(), Matchers.equalTo("0020000000000001"));
        MatcherAssert.assertThat(ctx.spanId(), Matchers.matchesRegex(HEX16));
        MatcherAssert.assertThat(ctx.spanId(), Matchers.not(Matchers.equalTo("0020000000000001")));
    }

    @Test
    void parsesB3SingleHeaderWithParent() {
        final Headers hdrs = Headers.from(
            "b3", "463ac35c9f6413ad-0020000000000001-1-00f067aa0ba902b7"
        );
        final SpanContext ctx = SpanContext.extract(hdrs);
        MatcherAssert.assertThat(ctx.traceId(), Matchers.equalTo("463ac35c9f6413ad"));
        MatcherAssert.assertThat(ctx.parentSpanId(), Matchers.equalTo("0020000000000001"));
        MatcherAssert.assertThat(ctx.spanId(), Matchers.matchesRegex(HEX16));
    }

    // ---- b3 multi headers ----

    @Test
    void parsesB3MultiHeaders() {
        final Headers hdrs = new Headers()
            .add("X-B3-TraceId", "463ac35c9f6413ad")
            .add("X-B3-SpanId", "0020000000000001");
        final SpanContext ctx = SpanContext.extract(hdrs);
        MatcherAssert.assertThat(ctx.traceId(), Matchers.equalTo("463ac35c9f6413ad"));
        MatcherAssert.assertThat(ctx.parentSpanId(), Matchers.equalTo("0020000000000001"));
        MatcherAssert.assertThat(ctx.spanId(), Matchers.matchesRegex(HEX16));
    }

    // ---- W3C traceparent ----

    @Test
    void parsesTraceparent() {
        final Headers hdrs = Headers.from(
            "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        );
        final SpanContext ctx = SpanContext.extract(hdrs);
        // W3C trace-id is 32 hex; take last 16
        MatcherAssert.assertThat(ctx.traceId(), Matchers.equalTo("a3ce929d0e0e4736"));
        MatcherAssert.assertThat(ctx.parentSpanId(), Matchers.equalTo("00f067aa0ba902b7"));
        MatcherAssert.assertThat(ctx.spanId(), Matchers.matchesRegex(HEX16));
    }

    // ---- fallback headers ----

    @Test
    void parsesXTraceId() {
        final Headers hdrs = Headers.from("X-Trace-Id", "463ac35c9f6413ad");
        final SpanContext ctx = SpanContext.extract(hdrs);
        MatcherAssert.assertThat(ctx.traceId(), Matchers.equalTo("463ac35c9f6413ad"));
        MatcherAssert.assertThat(ctx.parentSpanId(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.spanId(), Matchers.matchesRegex(HEX16));
    }

    @Test
    void parsesXRequestId() {
        final Headers hdrs = Headers.from("X-Request-Id", "463ac35c9f6413ad");
        final SpanContext ctx = SpanContext.extract(hdrs);
        MatcherAssert.assertThat(ctx.traceId(), Matchers.equalTo("463ac35c9f6413ad"));
    }

    @Test
    void generatesWhenNoHeaders() {
        final SpanContext ctx = SpanContext.extract(Headers.EMPTY);
        MatcherAssert.assertThat(ctx.traceId(), Matchers.matchesRegex(HEX16));
        MatcherAssert.assertThat(ctx.spanId(), Matchers.matchesRegex(HEX16));
        MatcherAssert.assertThat(ctx.parentSpanId(), Matchers.nullValue());
    }

    // ---- malformed IDs ----

    @Test
    void regeneratesMalformedTraceId() {
        final Headers hdrs = Headers.from("X-Trace-Id", "NOT-A-HEX");
        final SpanContext ctx = SpanContext.extract(hdrs);
        MatcherAssert.assertThat(ctx.traceId(), Matchers.matchesRegex(HEX16));
    }

    @Test
    void regeneratesTooShortTraceId() {
        final Headers hdrs = Headers.from("X-Trace-Id", "abc123");
        final SpanContext ctx = SpanContext.extract(hdrs);
        MatcherAssert.assertThat(ctx.traceId(), Matchers.matchesRegex(HEX16));
    }

    @Test
    void regeneratesTooLongTraceId() {
        final Headers hdrs = Headers.from("X-Trace-Id", "463ac35c9f6413ad463ac35c9f6413ad");
        final SpanContext ctx = SpanContext.extract(hdrs);
        // 32-char hex: should be truncated to last 16
        MatcherAssert.assertThat(ctx.traceId(), Matchers.matchesRegex(HEX16));
    }

    // ---- all-zero rejection (W3C §3.2.2.2) ----

    @Test
    void regeneratesAllZeroTraceId() {
        final Headers hdrs = Headers.from("X-Trace-Id", "0000000000000000");
        final SpanContext ctx = SpanContext.extract(hdrs);
        MatcherAssert.assertThat(ctx.traceId(), Matchers.matchesRegex(HEX16));
        MatcherAssert.assertThat(ctx.traceId(), Matchers.not(Matchers.equalTo("0000000000000000")));
    }

    @Test
    void regeneratesAllZero32CharTraceId() {
        final Headers hdrs = Headers.from(
            "traceparent", "00-00000000000000000000000000000000-abcdef1234567890-01"
        );
        final SpanContext ctx = SpanContext.extract(hdrs);
        MatcherAssert.assertThat(ctx.traceId(), Matchers.matchesRegex(HEX16));
        MatcherAssert.assertThat(ctx.traceId(), Matchers.not(Matchers.equalTo("0000000000000000")));
    }

    @Test
    void regeneratesAllZeroSpanId() {
        final Headers hdrs = Headers.from(
            "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01"
        );
        final SpanContext ctx = SpanContext.extract(hdrs);
        MatcherAssert.assertThat(ctx.parentSpanId(), Matchers.matchesRegex(HEX16));
        MatcherAssert.assertThat(ctx.parentSpanId(), Matchers.not(Matchers.equalTo("0000000000000000")));
    }

    // ---- W3C version byte validation ----

    @Test
    void rejectsInvalidW3cVersion() {
        final Headers hdrs = Headers.from(
            "traceparent", "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        );
        final SpanContext ctx = SpanContext.extract(hdrs);
        MatcherAssert.assertThat(ctx.traceId(), Matchers.matchesRegex(HEX16));
        MatcherAssert.assertThat(ctx.parentSpanId(), Matchers.nullValue());
    }

    @Test
    void rejectsFutureW3cVersion() {
        final Headers hdrs = Headers.from(
            "traceparent", "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        );
        final SpanContext ctx = SpanContext.extract(hdrs);
        MatcherAssert.assertThat(ctx.traceId(), Matchers.matchesRegex(HEX16));
        MatcherAssert.assertThat(ctx.parentSpanId(), Matchers.nullValue());
    }

    // ---- malformed B3 single (structural) ----

    @Test
    void handlesStructurallyMalformedB3Single() {
        final Headers hdrs = Headers.from("b3", "abc");
        final SpanContext ctx = SpanContext.extract(hdrs);
        MatcherAssert.assertThat(ctx.traceId(), Matchers.matchesRegex(HEX16));
        MatcherAssert.assertThat(ctx.spanId(), Matchers.matchesRegex(HEX16));
    }

    @Test
    void handlesStructurallyMalformedTraceparent() {
        final Headers hdrs = Headers.from("traceparent", "not-enough-parts");
        final SpanContext ctx = SpanContext.extract(hdrs);
        MatcherAssert.assertThat(ctx.traceId(), Matchers.matchesRegex(HEX16));
        MatcherAssert.assertThat(ctx.parentSpanId(), Matchers.nullValue());
    }

    // ---- precedence ----

    @Test
    void b3SingleTakesPrecedenceOverMulti() {
        final Headers hdrs = new Headers()
            .add("b3", "aaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-1")
            .add("X-B3-TraceId", "cccccccccccccccc")
            .add("X-B3-SpanId", "dddddddddddddddd");
        final SpanContext ctx = SpanContext.extract(hdrs);
        MatcherAssert.assertThat(ctx.traceId(), Matchers.equalTo("aaaaaaaaaaaaaaaa"));
        MatcherAssert.assertThat(ctx.parentSpanId(), Matchers.equalTo("bbbbbbbbbbbbbbbb"));
    }

    @Test
    void b3MultiTakesPrecedenceOverTraceparent() {
        final Headers hdrs = new Headers()
            .add("X-B3-TraceId", "aaaaaaaaaaaaaaaa")
            .add("X-B3-SpanId", "bbbbbbbbbbbbbbbb")
            .add("traceparent", "00-ccccccccccccccccdddddddddddddddd-eeeeeeeeeeeeeeee-01");
        final SpanContext ctx = SpanContext.extract(hdrs);
        MatcherAssert.assertThat(ctx.traceId(), Matchers.equalTo("aaaaaaaaaaaaaaaa"));
    }

    // ---- span.id never equals parent ----

    @Test
    void spanIdNeverEqualsParent() {
        final Headers hdrs = Headers.from("b3", "463ac35c9f6413ad-0020000000000001-1");
        for (int i = 0; i < 50; i++) {
            final SpanContext ctx = SpanContext.extract(hdrs);
            MatcherAssert.assertThat(
                "spanId must differ from parentSpanId",
                ctx.spanId(),
                Matchers.not(Matchers.equalTo(ctx.parentSpanId()))
            );
        }
    }
}
