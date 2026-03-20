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

import com.auto1.pantera.http.rq.RequestLine;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.hamcrest.core.IsEqual;

import java.net.URI;

/**
 * Request line URI matcher.
 */
public final class RqLineHasUri extends TypeSafeMatcher<RequestLine> {

    /**
     * Request line URI matcher.
     */
    private final Matcher<URI> target;

    /**
     * Match request line against URI matcher.
     * @param target URI matcher
     */
    public RqLineHasUri(final Matcher<URI> target) {
        this.target = target;
    }

    @Override
    public boolean matchesSafely(final RequestLine item) {
        return this.target.matches(item.uri());
    }

    @Override
    public void describeTo(final Description description) {
        description.appendText("URI ").appendDescriptionOf(this.target);
    }

    @Override
    public void describeMismatchSafely(final RequestLine item, final Description description) {
        this.target.describeMismatch(item.uri(), description.appendText("URI was: "));
    }

    /**
     * URI path matcher.
     * @since 0.10
     */
    public static final class HasPath extends TypeSafeMatcher<URI> {

        /**
         * URI path matcher.
         */
        private final Matcher<String> target;

        /**
         * Match URI against expected path.
         * @param path Expected path
         */
        public HasPath(final String path) {
            this(new IsEqual<>(path));
        }

        /**
         * Match URI against path matcher.
         * @param target Path matcher
         */
        public HasPath(final Matcher<String> target) {
            this.target = target;
        }

        @Override
        public boolean matchesSafely(final URI item) {
            return this.target.matches(item.getPath());
        }

        @Override
        public void describeTo(final Description description) {
            description.appendText("path ").appendDescriptionOf(this.target);
        }

        @Override
        public void describeMismatchSafely(final URI item, final Description description) {
            description.appendText("path was: ").appendValue(item.getPath());
        }
    }
}
