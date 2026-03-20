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
package com.auto1.pantera.http.hm;

import com.auto1.pantera.http.Response;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.hamcrest.core.IsEqual;

/**
 * Matcher to verify response body.
 */
public final class RsHasBody extends TypeSafeMatcher<Response> {

    /**
     * Body matcher.
     */
    private final Matcher<byte[]> body;

    /**
     * @param body Body to match
     */
    public RsHasBody(final byte[] body) {
        this(new IsEqual<>(body));
    }

    /**
     * @param body Body matcher
     */
    public RsHasBody(final Matcher<byte[]> body) {
        this.body = body;
    }

    @Override
    public void describeTo(final Description description) {
        description.appendDescriptionOf(this.body);
    }

    @Override
    public boolean matchesSafely(final Response item) {
        return this.body.matches(item.body().asBytes());
    }
}
