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
package com.auto1.pantera.http.log;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Tests for {@link RequestContextHeaders#bindToMdc(Headers)}. Worker threads
 * are pooled and can still carry MDC values of an earlier, unrelated request;
 * the request's own context headers must replace them, otherwise audit
 * records are correlated with the wrong request.
 */
final class RequestContextHeadersTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void requestHeadersReplaceLeftoverThreadContext() {
        MDC.put(EcsMdc.TRACE_ID, "trace-of-an-earlier-request");
        MDC.put(EcsMdc.CLIENT_IP, "192.0.2.1");
        RequestContextHeaders.bindToMdc(
            new Headers()
                .add(EcsLoggingSlice.CTX_TRACE_ID_HEADER, "trace-of-this-request")
                .add(EcsLoggingSlice.CTX_CLIENT_IP_HEADER, "10.0.0.5")
        );
        MatcherAssert.assertThat(
            "trace.id is the one of the request being processed",
            MDC.get(EcsMdc.TRACE_ID), new IsEqual<>("trace-of-this-request")
        );
        MatcherAssert.assertThat(
            "client.ip is the one of the request being processed",
            MDC.get(EcsMdc.CLIENT_IP), new IsEqual<>("10.0.0.5")
        );
    }

    @Test
    void keepsThreadContextWhenHeadersAreAbsent() {
        MDC.put(EcsMdc.TRACE_ID, "request-thread-trace");
        RequestContextHeaders.bindToMdc(Headers.EMPTY);
        MatcherAssert.assertThat(
            MDC.get(EcsMdc.TRACE_ID), new IsEqual<>("request-thread-trace")
        );
    }
}
