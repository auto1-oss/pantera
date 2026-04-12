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
import com.auto1.pantera.http.log.EcsMdc;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Tests for {@link TraceHeaders}.
 * @since 2.1.0
 */
final class TraceHeadersTest {

    @BeforeEach
    void setUp() {
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void injectsB3AndW3cHeaders() {
        MDC.put(EcsMdc.TRACE_ID, "aabbccddaabbccdd");
        MDC.put(EcsMdc.SPAN_ID, "1122334455667788");
        final Headers result = TraceHeaders.inject(Headers.EMPTY);
        MatcherAssert.assertThat(
            result.values("X-B3-TraceId").getFirst(),
            Matchers.equalTo("aabbccddaabbccdd")
        );
        MatcherAssert.assertThat(
            result.values("X-B3-ParentSpanId").getFirst(),
            Matchers.equalTo("1122334455667788")
        );
        MatcherAssert.assertThat(
            result.values("X-B3-SpanId").getFirst(),
            Matchers.matchesRegex("[\\da-f]{16}")
        );
        final String traceparent = result.values("traceparent").getFirst();
        MatcherAssert.assertThat(traceparent, Matchers.startsWith("00-aabbccddaabbccdd-"));
        MatcherAssert.assertThat(traceparent, Matchers.endsWith("-01"));
    }

    @Test
    void returnsOriginalWhenNoMdc() {
        final Headers original = Headers.from("Existing", "value");
        final Headers result = TraceHeaders.inject(original);
        MatcherAssert.assertThat(result, Matchers.equalTo(original));
    }

    @Test
    void httpClientHeadersReturnsAlternatingPairs() {
        MDC.put(EcsMdc.TRACE_ID, "aabbccddaabbccdd");
        MDC.put(EcsMdc.SPAN_ID, "1122334455667788");
        final String[] pairs = TraceHeaders.httpClientHeaders();
        MatcherAssert.assertThat(pairs.length, Matchers.equalTo(8));
        MatcherAssert.assertThat(pairs[0], Matchers.equalTo("X-B3-TraceId"));
        MatcherAssert.assertThat(pairs[1], Matchers.equalTo("aabbccddaabbccdd"));
        MatcherAssert.assertThat(pairs[6], Matchers.equalTo("traceparent"));
    }

    @Test
    void httpClientHeadersReturnsEmptyWhenNoMdc() {
        final String[] pairs = TraceHeaders.httpClientHeaders();
        MatcherAssert.assertThat(pairs.length, Matchers.equalTo(0));
    }
}
