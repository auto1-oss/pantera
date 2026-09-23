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
package com.auto1.pantera.scheduling;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Tests for {@link ArtifactEvent#withRequestContext(Headers)}: publish events
 * are built after async hops, on threads whose MDC is empty or left over from
 * an unrelated request, so the request's own context headers must win.
 */
final class ArtifactEventRequestContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void takesTraceAndClientIpFromRequestHeaders() {
        final ArtifactEvent event = ArtifactEventRequestContextTest.event()
            .withRequestContext(ArtifactEventRequestContextTest.ctxHeaders());
        MatcherAssert.assertThat(
            "trace.id comes from the request context header",
            event.traceId(), new IsEqual<>("trace-of-this-request")
        );
        MatcherAssert.assertThat(
            "client.ip comes from the request context header",
            event.clientIp(), new IsEqual<>("10.1.2.3")
        );
    }

    @Test
    void requestHeadersOverrideStaleThreadContext() {
        MDC.put(EcsMdc.TRACE_ID, "stale-trace-of-another-request");
        MDC.put(EcsMdc.CLIENT_IP, "192.0.2.99");
        final ArtifactEvent event = ArtifactEventRequestContextTest.event()
            .withRequestContext(ArtifactEventRequestContextTest.ctxHeaders());
        MatcherAssert.assertThat(
            "a leftover MDC trace.id must not reach the audit record",
            event.traceId(), new IsEqual<>("trace-of-this-request")
        );
        MatcherAssert.assertThat(
            "a leftover MDC client.ip must not reach the audit record",
            event.clientIp(), new IsEqual<>("10.1.2.3")
        );
    }

    @Test
    void keepsCapturedContextWhenHeadersAreAbsent() {
        MDC.put(EcsMdc.TRACE_ID, "captured");
        final ArtifactEvent event = ArtifactEventRequestContextTest.event()
            .withRequestContext(Headers.EMPTY);
        MatcherAssert.assertThat(
            "no header keeps the value captured at construction",
            event.traceId(), new IsEqual<>("captured")
        );
        MatcherAssert.assertThat(
            "no header and no MDC leaves client.ip unset",
            event.clientIp(), new IsNull<>()
        );
    }

    @Test
    void genericUploadEventCarriesRequestContext() {
        final java.util.Queue<ArtifactEvent> queue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
        new RepositoryEvents("file", "files", queue).addUploadEventByKey(
            new com.auto1.pantera.asto.Key.From("dir/a-1.0.txt"), 3L,
            ArtifactEventRequestContextTest.ctxHeaders()
        );
        final ArtifactEvent event = queue.poll();
        MatcherAssert.assertThat(
            "file/conan upload events carry the request trace.id",
            event.traceId(), new IsEqual<>("trace-of-this-request")
        );
        MatcherAssert.assertThat(
            "file/conan upload events carry the request client.ip",
            event.clientIp(), new IsEqual<>("10.1.2.3")
        );
    }

    private static ArtifactEvent event() {
        return new ArtifactEvent(
            "maven", "my-maven", "alice", "com.acme:lib", "1.0", 10L, 1L, null, "com/acme"
        );
    }

    private static Headers ctxHeaders() {
        return new Headers()
            .add(EcsLoggingSlice.CTX_TRACE_ID_HEADER, "trace-of-this-request")
            .add(EcsLoggingSlice.CTX_CLIENT_IP_HEADER, "10.1.2.3");
    }
}
