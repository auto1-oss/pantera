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
package com.auto1.pantera.gem.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import com.auto1.pantera.scheduling.ArtifactEvent;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SubmitGemSlice}.
 */
final class SubmitGemSliceTest {

    @Test
    void publishEventCarriesRequestContext() {
        // B36: the event is built after the save + JRuby index hops, where
        // the thread MDC no longer holds this request's trace.id/client.ip.
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        new SubmitGemSlice(new InMemoryStorage(), Optional.of(events), "my-gems").response(
            new RequestLine(RqMethod.POST, "/api/v1/gems"),
            Headers.from(
                new Header(EcsLoggingSlice.CTX_TRACE_ID_HEADER, "trace-gem"),
                new Header(EcsLoggingSlice.CTX_CLIENT_IP_HEADER, "10.0.0.1")
            ),
            new Content.From(new TestResource("builder-3.2.4.gem").asBytes())
        ).join();
        final ArtifactEvent event = events.poll();
        MatcherAssert.assertThat(
            "the gem publish event carries the request trace.id",
            event.traceId(), new IsEqual<>("trace-gem")
        );
        MatcherAssert.assertThat(
            "the gem publish event carries the request client.ip",
            event.clientIp(), new IsEqual<>("10.0.0.1")
        );
    }
}
