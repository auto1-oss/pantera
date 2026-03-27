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

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;

/**
 * Route by HTTP methods rule.
 */
public final class MethodRule implements RtRule {

    public static final RtRule GET = new MethodRule(RqMethod.GET);
    public static final RtRule HEAD = new MethodRule(RqMethod.HEAD);
    public static final RtRule POST = new MethodRule(RqMethod.POST);
    public static final RtRule PUT = new MethodRule(RqMethod.PUT);
    public static final RtRule PATCH = new MethodRule(RqMethod.PATCH);
    public static final RtRule DELETE = new MethodRule(RqMethod.DELETE);

    private final RqMethod method;

    private MethodRule(RqMethod method) {
        this.method = method;
    }

    @Override
    public boolean apply(RequestLine line, Headers headers) {
        return this.method == line.method();
    }
}
