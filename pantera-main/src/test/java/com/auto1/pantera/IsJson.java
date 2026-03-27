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
package com.auto1.pantera;

import java.io.ByteArrayInputStream;
import javax.json.Json;
import javax.json.JsonReader;
import javax.json.JsonValue;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

/**
 * Matcher for bytes array representing JSON.
 *
 * @since 0.11
 */
public final class IsJson extends TypeSafeMatcher<byte[]> {

    /**
     * Matcher for JSON.
     */
    private final Matcher<? extends JsonValue> json;

    /**
     * Ctor.
     *
     * @param json Matcher for JSON.
     */
    public IsJson(final Matcher<? extends JsonValue> json) {
        this.json = json;
    }

    @Override
    public void describeTo(final Description description) {
        description.appendText("JSON ").appendDescriptionOf(this.json);
    }

    @Override
    public boolean matchesSafely(final byte[] bytes) {
        final JsonValue root;
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(bytes))) {
            root = reader.readValue();
        }
        return this.json.matches(root);
    }
}
