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
package com.auto1.pantera.asto.test;

import com.auto1.pantera.asto.Content;
import com.google.common.util.concurrent.Uninterruptibles;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.hamcrest.TypeSafeMatcher;

import java.nio.charset.Charset;
import java.util.concurrent.ExecutionException;

/**
 * Matcher for {@link Content}.
 * @since 0.24
 */
public final class ContentIs extends TypeSafeMatcher<Content> {

    /**
     * Byte array matcher.
     */
    private final Matcher<byte[]> matcher;

    /**
     * Content is a string with encoding.
     * @param expected String
     * @param enc Encoding charset
     */
    public ContentIs(final String expected, final Charset enc) {
        this(expected.getBytes(enc));
    }

    /**
     * Content is a byte array.
     * @param expected Byte array
     */
    public ContentIs(final byte[] expected) {
        this(Matchers.equalTo(expected));
    }

    /**
     * Content matches for byte array matcher.
     * @param matcher Byte array matcher
     */
    public ContentIs(final Matcher<byte[]> matcher) {
        this.matcher = matcher;
    }

    @Override
    public void describeTo(final Description description) {
        description.appendText("has bytes ").appendValue(this.matcher);
    }

    @Override
    public boolean matchesSafely(final Content item) {
        try {
            return this.matcher.matches(
                Uninterruptibles.getUninterruptibly(item.asBytesFuture())
            );
        } catch (final ExecutionException err) {
            throw new IllegalStateException("Failed to read content", err);
        }
    }
}
