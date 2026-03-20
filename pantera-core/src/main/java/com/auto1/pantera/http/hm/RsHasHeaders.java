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
import com.auto1.pantera.http.headers.Header;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.hamcrest.TypeSafeMatcher;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Matcher to verify response headers.
 */
public final class RsHasHeaders extends TypeSafeMatcher<Response> {

    /**
     * Headers matcher.
     */
    private final Matcher<? extends Iterable<? extends Header>> headers;

    /**
     * @param headers Expected headers in any order.
     */
    public RsHasHeaders(Header... headers) {
        this(Arrays.asList(headers));
    }

    /**
     * @param headers Expected header matchers in any order.
     */
    public RsHasHeaders(final Iterable<? extends Header> headers) {
        this(transform(headers));
    }

    /**
     * @param headers Expected header matchers in any order.
     */
    @SafeVarargs
    public RsHasHeaders(Matcher<? super Header>... headers) {
        this(Matchers.hasItems(headers));
    }

    /**
     * @param headers Headers matcher
     */
    public RsHasHeaders(Matcher<? extends Iterable<? extends Header>> headers) {
        this.headers = headers;
    }

    @Override
    public void describeTo(final Description description) {
        description.appendDescriptionOf(this.headers);
    }

    @Override
    public boolean matchesSafely(final Response item) {
        return this.headers.matches(item.headers());
    }

    @Override
    public void describeMismatchSafely(final Response item, final Description desc) {
        desc.appendText("was ").appendValue(item.headers().asString());
    }

    /**
     * Transforms expected headers to expected header matchers.
     * This method is necessary to avoid compilation error.
     *
     * @param headers Expected headers in any order.
     * @return Expected header matchers in any order.
     */
    private static Matcher<? extends Iterable<Header>> transform(Iterable<? extends Header> headers) {
        return Matchers.allOf(
            StreamSupport.stream(headers.spliterator(), false)
                .map(Matchers::hasItem)
                .collect(Collectors.toList())
        );
    }
}
