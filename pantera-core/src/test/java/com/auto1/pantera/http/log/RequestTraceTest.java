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
import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RequestTrace}.
 *
 * @since 2.2.12
 */
final class RequestTraceTest {

    @Test
    void keepsTheStepsOfATraceInTheOrderTheyWereRecorded() {
        final RequestTrace traces = new RequestTrace();
        final String trace = traces.open();
        final Headers request = Headers.from(RequestTrace.HEADER, trace);
        final RequestTrace.Step member =
            new RequestTrace.Step("maven-central", "maven-proxy", 404, "negative_cache", 1L);
        final RequestTrace.Step group =
            new RequestTrace.Step("libs-release", "maven-group", 404, "negative_cache", 3L);
        traces.step(request, member);
        traces.step(request, group);
        MatcherAssert.assertThat(traces.close(trace), new IsEqual<>(List.of(member, group)));
    }

    @Test
    void aRequestThatIsNotTracedRecordsNothing() {
        final RequestTrace traces = new RequestTrace();
        final String trace = traces.open();
        traces.step(Headers.EMPTY, new RequestTrace.Step("a", "file", 404, "not_found", 1L));
        MatcherAssert.assertThat(
            "nothing was recorded",
            traces.close(trace), new IsEqual<>(List.of())
        );
        MatcherAssert.assertThat(
            "and such a request is not traced",
            traces.traced(Headers.EMPTY), new IsEqual<>(false)
        );
    }

    @Test
    void aTraceIdNobodyOpenedRecordsNothing() {
        final RequestTrace traces = new RequestTrace();
        final Headers forged = Headers.from(RequestTrace.HEADER, "forged");
        traces.step(forged, new RequestTrace.Step("a", "file", 404, "not_found", 1L));
        MatcherAssert.assertThat(traces.close("forged"), new IsEqual<>(List.of()));
    }

    @Test
    void closingATraceForgetsIt() {
        final RequestTrace traces = new RequestTrace();
        final String trace = traces.open();
        final Headers request = Headers.from(RequestTrace.HEADER, trace);
        traces.step(request, new RequestTrace.Step("a", "file", 404, "not_found", 1L));
        traces.close(trace);
        traces.step(request, new RequestTrace.Step("a", "file", 404, "not_found", 1L));
        MatcherAssert.assertThat(traces.close(trace), new IsEqual<>(List.of()));
    }
}
