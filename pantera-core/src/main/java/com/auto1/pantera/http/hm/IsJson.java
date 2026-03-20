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

import java.io.ByteArrayInputStream;
import javax.json.Json;
import javax.json.JsonReader;
import javax.json.JsonStructure;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

/**
 * Body matcher for JSON.
 * @since 1.0
 */
public final class IsJson extends TypeSafeMatcher<byte[]> {

    /**
     * Json matcher.
     */
    private final Matcher<? extends JsonStructure> matcher;

    /**
     * New JSON body matcher.
     * @param matcher JSON structure matcher
     */
    public IsJson(final Matcher<? extends JsonStructure> matcher) {
        this.matcher = matcher;
    }

    @Override
    public void describeTo(final Description desc) {
        desc.appendText("JSON ").appendDescriptionOf(this.matcher);
    }

    @Override
    public boolean matchesSafely(final byte[] body) {
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(body))) {
            return this.matcher.matches(reader.read());
        }
    }

    @Override
    public void describeMismatchSafely(final byte[] item, final Description desc) {
        desc.appendText("was ").appendValue(new String(item));
    }
}
