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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import io.reactivex.Flowable;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

import java.util.function.Function;

/**
 * Matcher for {@link Slice} response.
 * @since 0.16
 */
public final class SliceHasResponse extends TypeSafeMatcher<Slice> {

    /**
     * Response matcher.
     */
    private final Matcher<? extends Response> rsp;

    /**
     * Function to get response from slice.
     */
    private final Function<? super Slice, ? extends Response> responser;

    /**
     * Response cache.
     */
    private Response response;

    /**
     * New response matcher for slice with request line.
     * @param rsp Response matcher
     * @param line Request line
     */
    public SliceHasResponse(final Matcher<? extends Response> rsp, final RequestLine line) {
        this(rsp, line, Headers.EMPTY, new Content.From(Flowable.empty()));
    }

    /**
     * New response matcher for slice with request line.
     *
     * @param rsp Response matcher
     * @param headers Headers
     * @param line Request line
     */
    public SliceHasResponse(Matcher<? extends Response> rsp, Headers headers, RequestLine line) {
        this(rsp, line, headers, new Content.From(Flowable.empty()));
    }

    /**
     * New response matcher for slice with request line, headers and body.
     * @param rsp Response matcher
     * @param line Request line
     * @param headers Headers
     * @param body Body
     */
    public SliceHasResponse(
        Matcher<? extends Response> rsp,
        RequestLine line,
        Headers headers,
        Content body
    ) {
        this.rsp = rsp;
        this.responser = slice -> slice.response(line, headers, body).join();
    }

    @Override
    public boolean matchesSafely(final Slice item) {
        return this.rsp.matches(this.response(item));
    }

    @Override
    public void describeTo(final Description description) {
        description.appendText("response: ").appendDescriptionOf(this.rsp);
    }

    @Override
    public void describeMismatchSafely(final Slice item, final Description description) {
        description.appendText("response was: ").appendValue(this.response(item));
    }

    /**
     * Response for slice.
     * @param slice Target slice
     * @return Cached response
     */
    private Response response(final Slice slice) {
        if (this.response == null) {
            this.response = this.responser.apply(slice);
        }
        return this.response;
    }
}
