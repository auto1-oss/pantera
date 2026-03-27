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
package com.auto1.pantera.http.rt;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Rule-based route path.
 * <p>
 * A path to slice with routing rule. If
 * {@link RtRule} passed, then the request will be redirected to
 * underlying {@link Slice}.
 */
public final class RtRulePath implements RtPath {

    public static RtPath route(RtRule method, Pattern pathPattern, Slice action) {
        return new RtRulePath(
            new RtRule.All(new RtRule.ByPath(pathPattern), method),
            action
        );
    }

    /**
     * Routing rule.
     */
    private final RtRule rule;

    /**
     * Slice under route.
     */
    private final Slice slice;

    /**
     * New routing path.
     * @param rule Rules to apply
     * @param slice Slice to call
     */
    public RtRulePath(final RtRule rule, final Slice slice) {
        this.rule = rule;
        this.slice = slice;
    }

    @Override
    public Optional<CompletableFuture<Response>> response(RequestLine line, Headers headers, Content body) {
        if (this.rule.apply(line, headers)) {
            return Optional.of(this.slice.response(line, headers, body));
        }
        return Optional.empty();
    }
}
