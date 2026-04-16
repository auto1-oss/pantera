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
package com.auto1.pantera.http.context;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Minimal record-accessor smoke test for the WI-01 scaffold of
 * {@link RequestContext}. WI-02 will replace this with an exhaustive
 * ECS-field / ThreadContext / APM propagation contract test.
 */
final class RequestContextTest {

    @Test
    void recordAccessorsReturnConstructorValues() {
        final RequestContext ctx = new RequestContext(
            "trace-abc", "req-1", "npm_group", "/npm/@scope/pkg"
        );
        MatcherAssert.assertThat(ctx.traceId(), Matchers.is("trace-abc"));
        MatcherAssert.assertThat(ctx.httpRequestId(), Matchers.is("req-1"));
        MatcherAssert.assertThat(ctx.repoName(), Matchers.is("npm_group"));
        MatcherAssert.assertThat(ctx.urlOriginal(), Matchers.is("/npm/@scope/pkg"));
    }

    @Test
    void recordEqualityFollowsRecordSemantics() {
        final RequestContext a = new RequestContext("t", "r", "repo", "/u");
        final RequestContext b = new RequestContext("t", "r", "repo", "/u");
        MatcherAssert.assertThat(a, Matchers.is(b));
        MatcherAssert.assertThat(a.hashCode(), Matchers.is(b.hashCode()));
    }
}
