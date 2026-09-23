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
package com.auto1.pantera.http;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.settings.MetricsContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Every repository listener is built on {@link BaseSlice}; a path with a
 * {@code ..} segment must be answered 400 there, before any adapter runs.
 *
 * @since 2.2.9
 */
final class BaseSliceTraversalTest {

    @Test
    void rejectsEncodedParentSegmentBeforeTheRepository() {
        final AtomicBoolean reached = new AtomicBoolean();
        final Response rsp = new BaseSlice(
            new MetricsContext(Yaml.createYamlMappingBuilder().build()),
            (line, headers, body) -> {
                reached.set(true);
                return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
            }
        ).response(
            new RequestLine("PUT", "/test_prefix/api/go/%2e%2e/other/x.zip"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "traversal is a client error",
            rsp.status().code(), new IsEqual<>(400)
        );
        MatcherAssert.assertThat(
            "the repository slice is never reached",
            reached.get(), new IsEqual<>(false)
        );
    }
}
